package io.github.trevarj.motd

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.sync.COMMAND_RESPONSE_PAYLOAD_PREFIX
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.NetworkBatchPayloadV1
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.ui.chat.MessageList
import io.github.trevarj.motd.ui.chat.ReplyTarget
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class MessageTimelineUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun canonicalVariantsRenderOnceInTimelineOrder() {
        val rows =
            listOf(
                message(6, 600, MessageKind.PRIVMSG, "failed", self = true, failed = true),
                message(5, 500, MessageKind.PRIVMSG, "sending", self = true, pending = true),
                message(4, 400, MessageKind.NOTICE, "notice"),
                message(3, 300, MessageKind.ACTION, "waves"),
                message(2, 200, MessageKind.JOIN, "joined"),
                message(1, 100, MessageKind.PRIVMSG, "oldest"),
            )
        render(flowOf(PagingData.from(rows)), TimelineAnchor(250, 2))

        listOf(6L, 5L, 4L, 3L, 1L).forEach { assertMessageOnce(it) }
        scrollTo("chat_system_pill")
        compose.onAllNodesWithTag("chat_system_pill", useUnmergedTree = true).assertCountEquals(1)
        scrollTo("chat_read_marker_divider")
        compose.onAllNodesWithTag("chat_read_marker_divider", useUnmergedTree = true).assertCountEquals(1)
        val divider = bounds("chat_read_marker_divider")
        val firstUnread = bounds(messageTag(3))
        assertTrue("read marker divider must render above the first unread row", divider.bottom <= firstUnread.top)

        scrollTo(messageTag(4))
        val notice = bounds(messageTag(4))
        val action = bounds(messageTag(3))
        assertTrue("newer NOTICE must render below older ACTION in the reversed timeline", notice.top > action.top)
    }

    @Test
    fun differentNickAfterReplyStartsANewGroupEvenWithTheSameAccount() {
        val reply =
            message(1, 100, MessageKind.PRIVMSG, "first reply").copy(
                senderAccount = "shared",
                replyToMsgid = "parent",
            )
        val nextReply =
            message(2, 200, MessageKind.PRIVMSG, "second reply").copy(
                sender = "bob",
                normalizedActor = "bob",
                senderAccount = "shared",
                replyToMsgid = "parent",
            )
        val continuation = nextReply.copy(id = 3, msgid = "m3", serverTime = 300, text = "continued", replyToMsgid = null)
        render(flowOf(PagingData.from(listOf(continuation, nextReply, reply))))

        scrollTo(messageTag(2))
        compose
            .onNode(hasText("bob") and hasAnyAncestor(hasTestTag(messageTag(2))), useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNode(hasText("parent text") and hasAnyAncestor(hasTestTag(messageTag(2))), useUnmergedTree = true)
            .assertIsDisplayed()
        scrollTo(messageTag(3))
        compose
            .onNode(hasText("bob") and hasAnyAncestor(hasTestTag(messageTag(3))), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun eventOnlyReplyKeepsItsQuoteAndResolvesALateParent() {
        val target = ReplyTarget(eventId = 41)
        val parent = MutableStateFlow<ReplyPreviewData?>(null)
        val previews = mapOf(target to parent)
        var opened: ReplyTarget? = null
        val reply =
            message(2, 200, MessageKind.PRIVMSG, "child body", self = true).copy(
                replyToEventId = target.eventId,
                replyToMsgid = null,
            )
        val unrelated = message(3, 300, MessageKind.PRIVMSG, "unrelated body")
        render(
            flowOf(PagingData.from(listOf(unrelated, reply))),
            replyPreview = previews::getValue,
            onReplyPreviewClick = { opened = it },
        )

        scrollTo(messageTag(2))
        compose
            .onNodeWithTag("chat_reply_preview", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasNoClickAction()
        val unavailable = RuntimeEnvironment.getApplication().getString(R.string.chat_reply_target_unavailable)
        compose
            .onNode(hasText(unavailable) and hasAnyAncestor(hasTestTag("chat_reply_preview")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("child body", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnIdle {
            parent.value = ReplyPreviewData(sender = "parent nick", text = "original parent body")
        }

        compose
            .onNode(hasText("parent nick") and hasAnyAncestor(hasTestTag("chat_reply_preview")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNode(hasText("original parent body") and hasAnyAncestor(hasTestTag("chat_reply_preview")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText(unavailable, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("child body", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithTag("chat_reply_preview", useUnmergedTree = true)
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(ReplyTarget(eventId = 41), opened) }

        scrollTo(messageTag(3))
        compose.onNodeWithText("unrelated body", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNode(hasTestTag("chat_reply_preview") and hasAnyAncestor(hasTestTag(messageTag(3))), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun typedEventsExposeStableActionsAndFallbacks() {
        var joins = 0
        var dismissals = 0
        val rows =
            listOf(
                message(4, 400, MessageKind.DCC_TRANSFER, "file offer", payload = null),
                message(
                    3,
                    300,
                    MessageKind.NETJOIN,
                    "network healed",
                    payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("alice", "bob")).encode(),
                ),
                message(
                    2,
                    200,
                    MessageKind.NETSPLIT,
                    "network split",
                    payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("alice")).encode(),
                ),
                message(
                    1,
                    100,
                    MessageKind.INVITE,
                    "alice invited you",
                    payload = InvitePayloadV1("alice", "me", "#journey").encode(),
                    inviteState = InviteState.PENDING,
                ),
            )
        render(
            flowOf(PagingData.from(rows)),
            onAcceptInvite = { joins++ },
            onDismissInvite = { dismissals++ },
        )

        scrollTo("chat_invite_card_1")
        compose.onNodeWithTag("chat_invite_join_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("chat_invite_dismiss_1", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1, joins)
            assertEquals(1, dismissals)
        }
        listOf("chat_network_batch_netsplit_2", "chat_network_batch_netjoin_3", "chat_dcc_transfer_compact_4")
            .forEach { tag ->
                scrollTo(tag)
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
            }
    }

    @Test
    fun singleCommandReplyStartsCollapsedAndExpands() {
        val reply =
            message(
                1,
                100,
                MessageKind.SERVER_INFO,
                "Message of the day complete",
                payload = "${COMMAND_RESPONSE_PAYLOAD_PREFIX}motd-session",
            ).copy(sender = "/motd")
        render(flowOf(PagingData.from(listOf(reply))))

        scrollTo("chat_system_pill")
        compose.onNodeWithText("/motd · 1 reply", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Message of the day complete", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("chat_system_pill", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Message of the day complete", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun dickordModeCleansTimelineLabelsAndAudioOrigin() {
        val expanded =
            message(2, 400_000, MessageKind.PRIVMSG, "listen https://files.example/clip.mp3")
                .copy(
                    sender = "Alice/discord",
                    normalizedActor = "alice/discord",
                    replyToMsgid = "parent",
                )
        val collapsed =
            message(1, 100, MessageKind.PRIVMSG, "hidden")
                .copy(sender = "Carol/discord", normalizedActor = "carol/discord")
        var played: AudioPlaybackRequest? = null
        render(
            flowOf(PagingData.from(listOf(expanded, collapsed))),
            replyPreview = { MutableStateFlow(ReplyPreviewData("Bob/discord", "parent text")) },
            dickordEnabled = true,
            conversationName = "#discord.me.chat.alice",
            richContentReady = true,
            fools = setOf("Alice/discord", "Carol/discord"),
            foolExpanded = { it == expanded.id },
            onAudioToggle = { played = it },
        )

        scrollTo(messageTag(expanded.id))
        compose
            .onNode(hasText("Alice") and hasAnyAncestor(hasTestTag(messageTag(expanded.id))), useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNode(hasText("Bob") and hasAnyAncestor(hasTestTag("chat_reply_preview")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.chat_fool_collapse, "Alice"))
            .assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Download audio", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("Alice", played?.origin?.sender) }

        scrollTo(messageTag(collapsed.id))
        compose
            .onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.chat_fool_hidden, "Carol"))
            .assertIsDisplayed()
        listOf("Alice/discord", "Bob/discord", "Carol/discord").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun dickordLabelsStayRawWhileModeIsDisabled() {
        val row =
            message(1, 100, MessageKind.PRIVMSG, "child")
                .copy(
                    sender = "Alice/discord",
                    normalizedActor = "alice/discord",
                    replyToMsgid = "parent",
                )
        render(
            flowOf(PagingData.from(listOf(row))),
            replyPreview = { MutableStateFlow(ReplyPreviewData("Bob/discord", "parent text")) },
            conversationName = "#discord.me.chat.alice",
        )

        scrollTo(messageTag(row.id))
        compose.onNodeWithText("Alice/discord", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Bob/discord", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Alice", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Bob", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun pagingReplacementKeepsTheVisibleMessageAnchorStable() {
        val systemRun =
            listOf(
                message(1_002, 72_000, MessageKind.JOIN, "alice joined"),
                message(1_001, 71_000, MessageKind.PART, "bob left"),
            )
        val messages =
            (70L downTo 1L).map { id ->
                message(id, id * 1_000, MessageKind.PRIVMSG, "row $id")
            }
        val original = systemRun + messages
        val pages = MutableStateFlow(PagingData.from(original))
        render(pages)
        scrollTo("chat_system_pill")
        compose.onNodeWithTag("chat_system_pill", useUnmergedTree = true).performClick()
        compose.onNodeWithText("alice joined", useUnmergedTree = true).assertIsDisplayed()
        scrollTo(messageTag(25))
        val before = bounds(messageTag(25))

        pages.value =
            PagingData.from(
                listOf(message(1_003, 73_000, MessageKind.PRIVMSG, "row 71")) +
                    systemRun +
                    message(1_000, 70_500, MessageKind.QUIT, "carol quit") +
                    messages,
            )
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(messageTag(25), useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        }
        val after = bounds(messageTag(25))
        assertTrue("Paging replacement moved the visible keyed row", abs(after.top - before.top) <= 2f)
        compose.onAllNodesWithTag(messageTag(25), useUnmergedTree = true).assertCountEquals(1)
        assertTrue(bounds(messageTag(26)).top > after.top)
        assertTrue(bounds(messageTag(24)).top < after.top)

        scrollTo("chat_system_pill")
        compose.onNodeWithText("alice joined", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("bob left", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("carol quit", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun render(
        pages: Flow<PagingData<MessageEntity>>,
        marker: TimelineAnchor? = null,
        onAcceptInvite: (Long) -> Unit = {},
        onDismissInvite: (Long) -> Unit = {},
        replyPreview: (ReplyTarget) -> StateFlow<ReplyPreviewData?> = { MutableStateFlow(ReplyPreviewData("parent nick", "parent text")) },
        onReplyPreviewClick: (ReplyTarget) -> Unit = {},
        dickordEnabled: Boolean = false,
        conversationName: String? = null,
        richContentReady: Boolean = false,
        fools: Set<String> = emptySet(),
        foolExpanded: (Long) -> Boolean = { false },
        onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDickordLabsEnabled provides dickordEnabled) {
                MotdTheme(dynamicColor = false) {
                    MessageList(
                        items = pages.collectAsLazyPagingItems(context = Dispatchers.Unconfined),
                        listState = rememberLazyListState(),
                        networkId = 1,
                        bufferId = 1,
                        conversationName = conversationName,
                        readMarkerTime = marker,
                        onLongPress = {},
                        onReply = {},
                        onReact = { _, _ -> },
                        onImageClick = {},
                        onRetry = {},
                        loadPreview = { _, _ -> null },
                        richContentReady = richContentReady,
                        showImages = false,
                        showLinkPreviews = false,
                        onOpenLink = {},
                        onAudioToggle = onAudioToggle,
                        fools = fools,
                        foolsMode = FoolsMode.COLLAPSE,
                        foolExpanded = foolExpanded,
                        onAcceptInvite = onAcceptInvite,
                        replyPreview = replyPreview,
                        onReplyPreviewClick = onReplyPreviewClick,
                        onDismissInvite = onDismissInvite,
                    )
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("chat_timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertMessageOnce(id: Long) {
        val tag = messageTag(id)
        scrollTo(tag)
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("chat_timeline", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun messageTag(id: Long) = "chat_message_m$id"

    private fun message(
        id: Long,
        time: Long,
        kind: MessageKind,
        text: String,
        self: Boolean = false,
        failed: Boolean = false,
        pending: Boolean = false,
        payload: String? = null,
        inviteState: InviteState? = null,
    ) = MessageEntity(
        id = id,
        bufferId = 1,
        msgid = "m$id",
        serverTime = time,
        sender = if (self) "me" else "alice",
        kind = kind,
        text = text,
        isSelf = self,
        pendingLabel = if (pending) "pending-$id" else null,
        failed = failed,
        dedupKey = "dedup-$id",
        eventPayload = payload,
        inviteState = inviteState,
        timelineOrder = id,
    )
}
