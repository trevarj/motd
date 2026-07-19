package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.paging.LoadState
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.HistoryResyncState
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelsTest {

    private fun react(
        msgid: String,
        sender: String,
        emoji: String,
        actorKey: String = IrcIdentityRules().actorKey(sender, null),
    ) =
        ReactionEntity(
            bufferId = 1L,
            targetMsgid = msgid,
            actorKey = actorKey,
            sender = sender,
            emoji = emoji,
            serverTime = 0L,
        )

    private fun message(
        kind: MessageKind = MessageKind.PRIVMSG,
        self: Boolean = false,
        failed: Boolean = false,
        id: Long = 1L,
        sender: String = "nick",
        normalizedActor: String = IrcIdentityRules().normalize(sender),
        senderAccount: String? = null,
        serverTime: Long = 1L,
    ) =
        MessageEntity(
            id = id,
            bufferId = 1L,
            serverTime = serverTime,
            sender = sender,
            normalizedActor = normalizedActor,
            senderAccount = senderAccount,
            kind = kind,
            text = "text",
            isSelf = self,
            failed = failed,
            dedupKey = "1",
        )

    @Test fun `mine matches own nick case-insensitively`() {
        val chips = aggregateReactions(listOf(react("m1", "Alice", "👍")), myNick = "alice")
        assertTrue(chips.getValue("m1").single().mine)
    }

    @Test fun `mine uses rfc1459 casefolding for bracket chars`() {
        // nick[] and nick{} are the same nick under rfc1459 ( [ == { , ] == } ).
        val chips = aggregateReactions(listOf(react("m1", "nick[]", "🎉")), myNick = "nick{}")
        assertTrue("rfc1459 folding should treat []{} as equivalent", chips.getValue("m1").single().mine)
    }

    @Test fun `mine is false for a different reactor`() {
        val chips = aggregateReactions(listOf(react("m1", "bob", "👍")), myNick = "alice")
        assertFalse(chips.getValue("m1").single().mine)
    }

    @Test fun `mine is false when disconnected (null nick)`() {
        val chips = aggregateReactions(listOf(react("m1", "alice", "👍")), myNick = null)
        assertFalse(chips.getValue("m1").single().mine)
    }

    @Test fun `strict casemap does not merge tilde and caret reaction actors`() {
        val strict = IrcIdentityRules(IrcCaseMapping.Rfc1459Strict)
        val chips = aggregateReactions(
            listOf(react("m1", "nick~", "👍", strict.actorKey("nick~", null))),
            myNick = "nick^",
            identityRules = strict,
        )
        assertFalse(chips.getValue("m1").single().mine)
    }

    @Test fun `reaction ownership prefers exact account actor`() {
        val chips = aggregateReactions(
            listOf(react("m1", "oldNick", "👍", actorKey = "account:alice")),
            myNick = "newNick",
            myAccount = "alice",
        )
        assertTrue(chips.getValue("m1").single().mine)
    }

    @Test fun `persisted account owns reaction without a live nick`() {
        val chips = aggregateReactions(
            listOf(react("m1", "oldNick", "👍", actorKey = "account:alice")),
            myNick = null,
            myAccount = "alice",
        )
        assertTrue(chips.getValue("m1").single().mine)
    }

    @Test fun `counts aggregate per emoji preserving first-appearance order`() {
        val chips = aggregateReactions(
            listOf(
                react("m1", "a", "👍"),
                react("m1", "b", "👍"),
                react("m1", "c", "🎉"),
            ),
            myNick = "z",
        ).getValue("m1")
        assertEquals(listOf("👍", "🎉"), chips.map { it.emoji })
        assertEquals(2, chips[0].count)
        assertEquals(1, chips[1].count)
    }

    // Auto-stick-to-bottom decision (autoscroll-to-newest bug). Pin the reverse list to the newest
    // row only when the user was already at the bottom AND a new row actually arrived.
    @Test fun `autoscroll when at bottom and count grew`() {
        assertTrue(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 11))
    }

    @Test fun `initial paging page does not animate an already-bottom reverse list`() {
        assertFalse(shouldAutoscrollToNewest(atBottom = true, oldCount = 0, newCount = 50))
    }

    @Test fun `no autoscroll when scrolled up even if count grew`() {
        assertFalse(shouldAutoscrollToNewest(atBottom = false, oldCount = 10, newCount = 11))
    }

    @Test fun `no autoscroll when count did not grow`() {
        // Same count (e.g. an echo-confirm msgid swap) or a shrink must not yank the viewport.
        assertFalse(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 10))
        assertFalse(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 9))
    }

    @Test fun `burst arrivals keep following across programmatic scroll motion`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)

        assertTrue(tracker.onItemCountChanged(11))
        tracker.onScrollStateChanged(scrolling = true, programmatic = true, atBottom = false)

        // A second insert while the first pin is active must request another pin rather than
        // interpreting the programmatic scroll as the user leaving the bottom.
        assertTrue(tracker.onItemCountChanged(12))
        assertTrue(tracker.following)
    }

    @Test fun `user scroll disables following until settling at bottom`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)

        tracker.onScrollStateChanged(scrolling = true, programmatic = false, atBottom = true)
        assertFalse(tracker.onItemCountChanged(11))
        assertFalse(tracker.following)

        tracker.onScrollStateChanged(scrolling = false, programmatic = false, atBottom = true)
        assertTrue(tracker.onItemCountChanged(12))
    }

    @Test fun `initial paging reset is not treated as a live arrival`() {
        val tracker = AutoFollowTracker(initialItemCount = 0)

        tracker.reset(itemCount = 50, atBottom = true)
        assertFalse(tracker.onItemCountChanged(50))
        assertTrue(tracker.onItemCountChanged(51))
    }

    @Test fun `explicit newest request restores following`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.onScrollStateChanged(scrolling = true, programmatic = false, atBottom = false)

        tracker.requestFollow()

        assertTrue(tracker.onItemCountChanged(11))
    }

    @Test fun `ignored tail growth does not trigger follow until meaningful identity changes`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.reset(itemCount = 10, atBottom = true, newestEffectiveId = 7)

        assertFalse(tracker.onTimelineChanged(newItemCount = 11, newNewestEffectiveId = 7))
        assertTrue(tracker.onTimelineChanged(newItemCount = 12, newNewestEffectiveId = 8))
    }

    @Test fun `live entry animation is emitted only for a followed newer identity`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.reset(itemCount = 10, atBottom = true, newestEffectiveId = 7)

        assertNull(tracker.onTimelineChangedWithEntry(11, 7).liveEntryId)

        val live = tracker.onTimelineChangedWithEntry(12, 8)
        assertTrue(live.shouldFollow)
        assertEquals(8L, live.liveEntryId)

        tracker.onScrollStateChanged(scrolling = true, programmatic = false, atBottom = false)
        assertNull(tracker.onTimelineChangedWithEntry(13, 9).liveEntryId)
    }

    @Test fun `stale live entry completion preserves a newer pending entry`() {
        assertEquals(9L, consumeLiveEntryId(current = 9L, consumed = 8L))
        assertNull(consumeLiveEntryId(current = 9L, consumed = 9L))
    }

    @Test fun `paging invalidation cannot break following live arrivals`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.reset(itemCount = 10, atBottom = true, newestEffectiveId = 10)

        // Room invalidation can temporarily replace a populated Paging snapshot with an empty one.
        assertFalse(tracker.onTimelineChanged(newItemCount = 0, newNewestEffectiveId = null))

        assertTrue(tracker.onTimelineChanged(newItemCount = 11, newNewestEffectiveId = 11))
        assertTrue(tracker.following)
    }

    @Test fun `page replacement follows a newer identity even when loaded count stays constant`() {
        val tracker = AutoFollowTracker(initialItemCount = 50)
        tracker.reset(itemCount = 50, atBottom = true, newestEffectiveId = 50)

        assertTrue(tracker.onTimelineChanged(newItemCount = 50, newNewestEffectiveId = 51))
    }

    @Test fun `paging invalidation never overrides explicit user scroll intent`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.reset(itemCount = 10, atBottom = true, newestEffectiveId = 10)
        tracker.onScrollStateChanged(scrolling = true, programmatic = false, atBottom = false)

        assertFalse(tracker.onTimelineChanged(newItemCount = 0, newNewestEffectiveId = null))
        assertFalse(tracker.onTimelineChanged(newItemCount = 11, newNewestEffectiveId = 11))
        assertFalse(tracker.following)
    }

    @Test fun `random paging and scroll interleavings preserve follow intent for every layout`() {
        LayoutDensity.entries.forEachIndexed { layoutIndex, _ ->
            val random = Random(0xA170 + layoutIndex)
            val tracker = AutoFollowTracker(initialItemCount = 50)
            var newestId = 100L
            var count = 50
            var expectedFollowing = true
            tracker.reset(count, atBottom = true, newestEffectiveId = newestId)

            repeat(1_000) {
                when (random.nextInt(6)) {
                    0 -> assertFalse(tracker.onTimelineChanged(0, null))
                    1 -> {
                        newestId++
                        count = (count + random.nextInt(0, 2)).coerceAtLeast(1)
                        assertEquals(
                            expectedFollowing,
                            tracker.onTimelineChanged(count, newestId),
                        )
                    }
                    2 -> tracker.onScrollStateChanged(
                        scrolling = random.nextBoolean(),
                        programmatic = true,
                        atBottom = random.nextBoolean(),
                    )
                    3 -> {
                        tracker.onScrollStateChanged(
                            scrolling = true,
                            programmatic = false,
                            atBottom = false,
                        )
                        expectedFollowing = false
                    }
                    4 -> {
                        tracker.onScrollStateChanged(
                            scrolling = false,
                            programmatic = false,
                            atBottom = true,
                        )
                        expectedFollowing = true
                    }
                    else -> assertFalse(
                        tracker.onTimelineChanged(count, newestId - 1),
                    )
                }
                assertEquals(expectedFollowing, tracker.following)
            }
        }
    }

    @Test fun `collapsed fool tail counts as effective bottom and cannot become saved anchor`() {
        val rows = listOf(
            message(id = 3, sender = "fool"),
            message(id = 2, sender = "alice"),
            message(id = 1, sender = "bob"),
        )
        val policy = MessageVisibilityPolicy(
            MessageVisibilitySpec(fools = setOf("fool"), foolsMode = FoolsMode.COLLAPSE),
        )

        assertTrue(isAtEffectiveBottom(1, 0, rows.size, rows::getOrNull, policy))
        assertEquals(2L, newestEffectiveMessageId(rows.size, rows::getOrNull, policy))
        assertEquals(2L, nearestAnchorRow(0, rows.size, rows::getOrNull, policy)?.second?.id)
    }

    @Test fun `meaningful row below viewport means it is not effective bottom`() {
        val rows = listOf(message(id = 2), message(id = 1))
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec())

        assertFalse(isAtEffectiveBottom(1, 0, rows.size, rows::getOrNull, policy))
    }

    @Test fun `placeholder-aware helpers never scan a 50k unloaded timeline`() {
        var probes = 0
        val peek: (Int) -> MessageEntity? = {
            probes++
            null
        }
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec())

        assertNull(newestEffectiveMessageId(50_000, peek, policy))
        assertTrue(probes <= MAX_PLACEHOLDER_PROBES)
        probes = 0
        assertFalse(isAtEffectiveBottom(49_999, 0, 50_000, peek, policy))
        assertEquals(0, probes)
        assertNull(nearestAnchorRow(25_000, 50_000, peek, policy))
        assertTrue(probes <= MAX_PLACEHOLDER_PROBES * 2)
    }

    @Test fun `normal entry scrolls newest only when retained state is off bottom`() {
        assertFalse(shouldScrollToInitialTarget(ChatPositionTarget(index = 0), atBottom = true))
        assertTrue(shouldScrollToInitialTarget(ChatPositionTarget(index = 0), atBottom = false))
    }

    @Test fun `normal entry always scrolls to older unread target`() {
        assertTrue(shouldScrollToInitialTarget(ChatPositionTarget(index = 7), atBottom = true))
        assertTrue(shouldScrollToInitialTarget(ChatPositionTarget(index = 7), atBottom = false))
    }

    @Test fun `saved scroll position always restores`() {
        assertTrue(
            shouldScrollToInitialTarget(
                ChatPositionTarget(index = 0, offset = 20, fromSavedPosition = true),
                atBottom = true,
            ),
        )
    }

    @Test fun `composer does not need member nicks for blank text or command hints`() {
        assertFalse(composerNeedsMemberNicks(TextFieldValue("")))
        assertFalse(composerNeedsMemberNicks(TextFieldValue("/jo", TextRange(3))))
    }

    @Test fun `composer needs member nicks only for qualifying nick tokens`() {
        assertFalse(composerNeedsMemberNicks(TextFieldValue("a", TextRange(1))))
        assertTrue(composerNeedsMemberNicks(TextFieldValue("al", TextRange(2))))
        assertTrue(composerNeedsMemberNicks(TextFieldValue("@a", TextRange(2))))
    }

    @Test fun `lazy row content types separate structurally different messages`() {
        assertEquals(MessageContentType.OTHER, messageContentType(message()))
        assertEquals(MessageContentType.SELF, messageContentType(message(self = true)))
        assertEquals(MessageContentType.SELF_FAILED, messageContentType(message(self = true, failed = true)))
        assertEquals(MessageContentType.ACTION, messageContentType(message(kind = MessageKind.ACTION)))
        assertEquals(MessageContentType.SYSTEM, messageContentType(message(kind = MessageKind.JOIN)))
        assertEquals(MessageContentType.NETWORK_BATCH, messageContentType(message(kind = MessageKind.NETSPLIT)))
        assertEquals(MessageContentType.NETWORK_BATCH, messageContentType(message(kind = MessageKind.NETJOIN)))
    }

    @Test fun `grouping uses account then casemapped actor and always separates direction`() {
        val accountOlder = message(
            id = 1,
            sender = "OldNick",
            senderAccount = "alice",
            serverTime = 100,
        )
        val accountCurrent = message(
            id = 2,
            sender = "NewNick",
            senderAccount = "alice",
            serverTime = 200,
        )
        assertFalse(showsSender(accountCurrent, accountOlder))
        assertTrue(showsSender(accountCurrent.copy(senderAccount = "other"), accountOlder))

        val mappedOlder = message(id = 3, sender = "nick[]", normalizedActor = "nick{}", serverTime = 300)
        val mappedCurrent = message(id = 4, sender = "nick{}", normalizedActor = "nick{}", serverTime = 400)
        assertFalse(showsSender(mappedCurrent, mappedOlder))
        assertTrue(showsSender(mappedCurrent.copy(isSelf = true), mappedOlder))

        val partiallyEnriched = mappedCurrent.copy(senderAccount = "late-account")
        assertFalse(showsSender(partiallyEnriched, mappedOlder))
    }

    @Test fun `typed UI queue replays in order and acknowledges by stable id`() {
        val queue = ChatUiEventQueue()
        val first = queue.enqueue(ChatUiEvent.InvalidCommand)
        val second = queue.enqueue(ChatUiEvent.SendRejected)

        assertEquals(listOf(first, second), queue.pending.value)
        queue.acknowledge(first.id)
        assertEquals(listOf(second), queue.pending.value)
        queue.acknowledge(first.id)
        assertEquals(listOf(second), queue.pending.value)
    }

    @Test fun `typed snackbar handles retry before acknowledging and preserves exact reply request`() {
        val historyEvent = QueuedChatUiEvent(7, ChatUiEvent.HistoryIncomplete(3))
        val historyOrder = mutableListOf<String>()
        handleChatUiEventResult(
            event = historyEvent,
            actionPerformed = true,
            retryReplyJump = { historyOrder += "unexpected" },
            retryMissingHistory = { historyOrder += "retry" },
            acknowledge = { historyOrder += "ack:$it" },
        )
        assertEquals(listOf("retry", "ack:7"), historyOrder)

        val request = ReplyJumpRequest("MiXeD/opaque=Reply")
        var retried: ReplyJumpRequest? = null
        handleChatUiEventResult(
            event = QueuedChatUiEvent(8, ChatUiEvent.ReplyJumpUnavailable(request)),
            actionPerformed = true,
            retryReplyJump = { retried = it },
            retryMissingHistory = {},
            acknowledge = {},
        )
        assertEquals(request, retried)
    }

    @Test fun `history footer requires persisted completion and preserves partial outcomes`() {
        val ready = HistoryAvailability.Ready(setOf(HistoryReferenceType.MSGID), pageLimit = 50)
        val ended = LoadState.NotLoading(endOfPaginationReached = true)

        assertEquals(
            ChatHistoryUiState.Incomplete(),
            chatHistoryUiState(
                BufferType.CHANNEL,
                IrcClientState.Ready("me", emptySet(), emptyMap()),
                ready,
                ended,
                historyComplete = false,
                resync = HistoryResyncState.Idle,
            ),
        )
        assertEquals(
            ChatHistoryUiState.ConfirmedStart,
            chatHistoryUiState(
                BufferType.CHANNEL,
                IrcClientState.Ready("me", emptySet(), emptyMap()),
                ready,
                ended,
                historyComplete = true,
                resync = HistoryResyncState.Idle,
            ),
        )
        assertEquals(
            ChatHistoryUiState.Capped(inserted = 17, limit = 50),
            chatHistoryUiState(
                BufferType.CHANNEL,
                IrcClientState.Ready("me", emptySet(), emptyMap()),
                ready,
                ended,
                historyComplete = true,
                resync = HistoryResyncState.Capped(17, 50, "partial"),
            ),
        )
        assertEquals(
            ChatHistoryUiState.Incomplete(inserted = 9),
            chatHistoryUiState(
                BufferType.CHANNEL,
                IrcClientState.Ready("me", emptySet(), emptyMap()),
                ready,
                ended,
                historyComplete = true,
                resync = HistoryResyncState.Incomplete(9, "partial"),
            ),
        )
    }

    @Test fun `history footer distinguishes hidden loading availability and error states`() {
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val failed = LoadState.Error(IllegalStateException("offline"))
        val ready = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), pageLimit = 50)

        fun state(
            connection: IrcClientState?,
            availability: HistoryAvailability,
            append: LoadState = idle,
            bufferType: BufferType = BufferType.CHANNEL,
            resync: HistoryResyncState = HistoryResyncState.Idle,
        ) = chatHistoryUiState(
            bufferType,
            connection,
            availability,
            append,
            historyComplete = false,
            resync = resync,
        )

        assertEquals(
            ChatHistoryUiState.Hidden,
            state(IrcClientState.Disconnected, ready, bufferType = BufferType.SERVER),
        )
        assertEquals(
            ChatHistoryUiState.Loading,
            state(IrcClientState.Connecting, ready, append = LoadState.Loading),
        )
        assertEquals(
            ChatHistoryUiState.Offline,
            state(IrcClientState.Disconnected, HistoryAvailability.NegotiatingOrOffline, failed),
        )
        assertEquals(
            ChatHistoryUiState.Negotiating,
            state(IrcClientState.Registering, HistoryAvailability.NegotiatingOrOffline, failed),
        )
        assertEquals(
            ChatHistoryUiState.Unsupported,
            state(IrcClientState.Ready("me", emptySet(), emptyMap()), HistoryAvailability.Unsupported),
        )
        assertEquals(
            ChatHistoryUiState.Unsupported,
            state(
                IrcClientState.Ready("me", emptySet(), emptyMap()),
                HistoryAvailability.Unsupported,
                append = failed,
                resync = HistoryResyncState.Failed("stale"),
            ),
        )
        assertEquals(
            ChatHistoryUiState.Error,
            state(
                IrcClientState.Ready("me", emptySet(), emptyMap()),
                ready,
                resync = HistoryResyncState.Failed("failed"),
            ),
        )
    }

    @Test fun `offline failure retries once when Ready is first observed with the error`() {
        val gate = HistoryReadyRetryGate()
        val ready = HistoryAvailability.Ready(setOf(HistoryReferenceType.MSGID), pageLimit = 50)
        val offline = LoadState.Error(IrcDisconnectedException("CHATHISTORY", "offline"))

        assertTrue(gate.update(ready, offline))
        assertFalse(gate.update(ready, offline))
        assertFalse(gate.update(HistoryAvailability.Unsupported, offline))
        assertFalse(gate.update(ready, LoadState.Error(IllegalStateException("not offline"))))

        val nextGeneration = LoadState.Error(IrcDisconnectedException("CHATHISTORY", "offline again"))
        assertFalse(gate.update(HistoryAvailability.NegotiatingOrOffline, nextGeneration))
        assertTrue(gate.update(ready, nextGeneration))
        assertFalse(gate.update(ready, nextGeneration))
    }
}
