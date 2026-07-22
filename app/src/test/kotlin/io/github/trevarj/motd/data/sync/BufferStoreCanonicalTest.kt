package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(androidx.paging.ExperimentalPagingApi::class)
class BufferStoreCanonicalTest {
    private lateinit var db: MotdDatabase
    private lateinit var store: BufferStore
    private var networkId = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        store = BufferStore(db)
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "network",
                role = NetworkRole.DIRECT,
                host = "irc.example",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun differentAccountsReusingNickRemainSeparate() = runTest {
        val provisional = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        val first = store.bindQueryIdentity(provisional.id, networkId, "alice", "Alice", "acct-a")
        val second = store.bindQueryIdentity(first.id, networkId, "alice", "Alice", "acct-b")

        assertNotEquals(first.id, second.id)
        assertEquals("Alice", first.displayName)
        assertEquals("Alice", second.displayName)

        val firstAgain = store.bindQueryIdentity(first.id, networkId, "alice", "Alicia", "acct-a")
        val secondAgain = store.bindQueryIdentity(first.id, networkId, "alice", "Alice2", "acct-b")
        assertEquals(first.id, firstAgain.id)
        assertEquals(second.id, secondAgain.id)
        assertEquals("Alicia", firstAgain.displayName)
        assertEquals("Alice2", secondAgain.displayName)
    }

    @Test
    fun accountlessPmUsesLatestVerifiedNick_withoutMergingReusedAccounts() = runTest {
        val provisional = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        val accountA = store.bindQueryIdentity(
            provisional.id, networkId, "alice", "Alice A", "acct-a",
        )
        val accountB = store.bindQueryIdentity(
            accountA.id, networkId, "alice", "Alice B", "acct-b",
        )

        assertNotEquals(accountA.id, accountB.id)
        assertEquals(accountB.id, store.resolveQueryRoom(networkId, "alice", null)?.id)
        assertEquals(accountA.id, store.resolveQueryRoom(networkId, "alice", "acct-a")?.id)

        store.bindQueryIdentity(accountB.id, networkId, "alice", "Alice A2", "acct-a")
        assertEquals(accountA.id, store.resolveQueryRoom(networkId, "alice", null)?.id)
        assertEquals("Alice A2", db.bufferDao().observeById(accountA.id)?.displayName)
    }

    @Test
    fun nickChangeDoesNotMergeRoomsWithDifferentVerifiedAccounts() = runTest {
        val old = store.getOrCreate(networkId, "oldnick", "OldNick", BufferType.QUERY)
        val accountA = store.bindQueryIdentity(
            old.id, networkId, "oldnick", "OldNick", "acct-a",
        )
        val next = store.getOrCreate(networkId, "newnick", "NewNick", BufferType.QUERY)
        val accountB = store.bindQueryIdentity(
            next.id, networkId, "newnick", "NewNick", "acct-b",
        )

        val changed = checkNotNull(
            store.bindNickChange(networkId, "oldnick", "newnick", "NewNick"),
        )

        assertEquals(accountA.id, changed.id)
        assertNotEquals(accountA.id, accountB.id)
        assertEquals(accountA.id, store.resolveQueryRoom(networkId, "newnick", "acct-a")?.id)
        assertEquals(accountB.id, store.resolveQueryRoom(networkId, "newnick", "acct-b")?.id)
        assertEquals(2, db.bufferDao().observeChatList().first().size)
    }

    @Test
    fun knownAccountMergesExistingAccountlessNickRoom() = runTest {
        val alice = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        val accountRoom = store.bindQueryIdentity(
            alice.id, networkId, "alice", "Alice", "acct-a",
        )
        val provisionalBob = store.getOrCreate(networkId, "bob", "Bob", BufferType.QUERY)
        db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    bufferId = provisionalBob.id,
                    serverTime = 1_000,
                    sender = "bob",
                    kind = MessageKind.PRIVMSG,
                    text = "before account evidence",
                    dedupKey = "bob-before-account",
                ),
            ),
        )

        val bound = store.bindQueryIdentity(
            provisionalBob.id, networkId, "bob", "Bob", "acct-a",
        )

        assertEquals(accountRoom.id, bound.id)
        assertEquals(accountRoom.id, db.bufferDao().observeById(provisionalBob.id)?.id)
        assertEquals(1, db.messageDao().countForBuffer(accountRoom.id))
        assertEquals(1, db.bufferDao().observeChatList().first().size)
    }

    @Test
    fun channelPromotionClearsDismissedQueryState() = runTest {
        val query = store.getOrCreate(networkId, "#room", "#room", BufferType.QUERY)
        db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    bufferId = query.id,
                    msgid = "query-history",
                    serverTime = 100,
                    sender = "peer",
                    kind = MessageKind.PRIVMSG,
                    text = "old query",
                    dedupKey = "query-history",
                ),
            ),
        )
        db.bufferDao().deleteBuffer(query.id)

        val channel = store.getOrCreate(networkId, "#room", "#room", BufferType.CHANNEL)

        assertEquals(query.id, channel.id)
        assertEquals(BufferType.CHANNEL, channel.type)
        assertFalse(channel.dismissed)
        assertEquals(null, channel.historyDiscardedThroughMsgid)
        assertEquals(null, channel.historyDiscardedThroughTime)
        assertEquals(null, db.historyCursorDao().byRoom(channel.id))
        assertFalse(db.bufferDao().isDiscardedMessageId(channel.id, "query-history"))
        assertTrue(db.bufferDao().observeChatList().first().any { it.bufferId == channel.id })
    }

    @Test
    fun visibleQueryPromotionPreservesReadAndHistoryState() = runTest {
        val query = store.getOrCreate(networkId, "#visible", "#visible", BufferType.QUERY)
        db.bufferDao().update(
            query.copy(
                readMarkerTime = 100,
                localReadAnchorTime = 101,
                localUnreadFloorTime = 99,
                oldestFetchedTime = 50,
                historyComplete = true,
            ),
        )
        val cursor = HistoryCursorEntity(
            roomId = query.id,
            newestMsgid = "newest",
            newestServerTime = 100,
            oldestMsgid = "oldest",
            oldestServerTime = 50,
            historyComplete = true,
        )
        db.historyCursorDao().upsert(cursor)

        val channel = store.getOrCreate(networkId, "#visible", "#visible", BufferType.CHANNEL)

        assertEquals(100L, channel.readMarkerTime)
        assertEquals(101L, channel.localReadAnchorTime)
        assertEquals(99L, channel.localUnreadFloorTime)
        assertEquals(50L, channel.oldestFetchedTime)
        assertTrue(channel.historyComplete)
        assertEquals(cursor, db.historyCursorDao().byRoom(channel.id))
    }

    @Test
    fun historyDiscoveryOnlyDismissesNewQueries() = runTest {
        val visible = store.getOrCreate(networkId, "visible", "Visible", BufferType.QUERY)

        val existing = store.getOrCreate(
            networkId,
            "visible",
            "Visible",
            BufferType.QUERY,
            initiallyDismissed = true,
        )
        val discovered = store.getOrCreate(
            networkId,
            "discovered",
            "Discovered",
            BufferType.QUERY,
            initiallyDismissed = true,
        )

        assertEquals(visible.id, existing.id)
        assertFalse(existing.dismissed)
        assertTrue(discovered.dismissed)
    }

    @Test
    fun visibleRoomDoesNotInheritDismissedShellReadFloorOnMerge() = runTest {
        val shell = store.getOrCreate(networkId, "oldnick", "OldNick", BufferType.QUERY)
        db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    bufferId = shell.id,
                    msgid = "old",
                    serverTime = 100,
                    sender = "oldnick",
                    kind = MessageKind.PRIVMSG,
                    text = "old",
                    dedupKey = "old",
                ),
            ),
        )
        db.bufferDao().update(shell.copy(localUnreadFloorTime = 10_000))
        db.bufferDao().deleteBuffer(shell.id)
        val visible = store.getOrCreate(networkId, "newnick", "NewNick", BufferType.QUERY)

        val merged = store.mergeRooms(shell.id, visible.id)

        assertFalse(merged.dismissed)
        assertEquals(null, merged.localReadAnchorTime)
        assertEquals(null, merged.localUnreadFloorTime)
        assertEquals(100L, merged.historyDiscardedThroughTime)
    }

    @Test
    fun roomMergeRekeysExactFingerprintAliasesForMovedEvents() = runTest {
        val winner = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        val loser = store.getOrCreate(networkId, "bob", "Bob", BufferType.QUERY)
        val timeline = CanonicalTimelineStore(db)
        val original = MessageEntity(
            bufferId = loser.id,
            serverTime = 4_000,
            sender = "bob",
            normalizedActor = "bob",
            kind = MessageKind.PRIVMSG,
            text = "authoritative without msgid",
            dedupKey = "diagnostic-only",
        )
        val first = timeline.ingest(
            TimelineObservation(
                networkId,
                original,
                ObservationOrigin.HISTORY,
                1,
                batchId = "history-a",
                timeProvenance = TimeProvenance.SERVER_TAG,
            ),
        )

        store.mergeRooms(winner.id, loser.id)
        val replay = timeline.ingest(
            TimelineObservation(
                networkId,
                original.copy(bufferId = winner.id),
                ObservationOrigin.HISTORY,
                1,
                batchId = "history-b",
                timeProvenance = TimeProvenance.SERVER_TAG,
            ),
        )

        assertEquals(first.event.id, replay.event.id)
        assertEquals(1, db.messageDao().countForBuffer(winner.id))
    }

    @Test
    fun roomMergeKeepsUsingCanonicalIdAfterFirstAliasCollisionCoalescesMovedEvent() = runTest {
        val winner = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        val loser = store.getOrCreate(networkId, "bob", "Bob", BufferType.QUERY)
        val timeline = CanonicalTimelineStore(db)
        fun observation(roomId: Long, msgid: String?) = TimelineObservation(
            networkId = networkId,
            event = MessageEntity(
                bufferId = roomId,
                msgid = msgid,
                serverTime = 4_500,
                sender = "same",
                normalizedActor = "same",
                kind = MessageKind.PRIVMSG,
                text = "same authoritative event",
                dedupKey = "diagnostic-only",
            ),
            origin = ObservationOrigin.HISTORY,
            connectionGeneration = 1,
            batchId = "overlap",
            timeProvenance = TimeProvenance.SERVER_TAG,
        )
        timeline.ingest(observation(winner.id, msgid = null))
        timeline.ingest(observation(loser.id, msgid = "source-msgid"))

        val merged = store.mergeRooms(winner.id, loser.id)

        assertEquals(1, db.messageDao().countForBuffer(merged.id))
        assertEquals("source-msgid", db.messageDao().byMsgid(merged.id, "source-msgid")?.msgid)
    }

    @Test
    fun roomMergeCopiesLoserOnlyReactionsWithoutIdsAndDedupesActorEmojiCollisions() = runTest {
        val winner = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        val loser = store.getOrCreate(networkId, "bob", "Bob", BufferType.QUERY)
        db.reactionDao().upsert(
            ReactionEntity(
                bufferId = winner.id,
                targetMsgid = "parent",
                actorKey = "account:shared",
                sender = "Winner spelling",
                emoji = "same",
                serverTime = 100,
            ),
        )
        db.reactionDao().upsert(
            ReactionEntity(
                bufferId = loser.id,
                targetMsgid = "parent",
                actorKey = "account:shared",
                sender = "Loser duplicate",
                emoji = "same",
                serverTime = 200,
            ),
        )
        db.reactionDao().upsert(
            ReactionEntity(
                bufferId = loser.id,
                targetMsgid = "parent",
                actorKey = "nick:loser-only",
                sender = "Loser only",
                emoji = "unique",
                serverTime = 300,
            ),
        )

        val merged = store.mergeRooms(winner.id, loser.id)
        val rows = db.reactionDao().observeForBuffer(merged.id).first()

        assertEquals(2, rows.size)
        assertEquals("Winner spelling", rows.single { it.emoji == "same" }.sender)
        assertEquals("Loser only", rows.single { it.emoji == "unique" }.sender)
        assertTrue(rows.all { it.id > 0 })
        assertTrue(db.reactionDao().observeForBuffer(loser.id).first().isEmpty())
    }

    @Test
    fun nestedRoomMergeRetirementRunsOnlyAfterOuterCommit() = runTest {
        val retirements = mutableListOf<Pair<Long, Long>>()
        val notifyingStore = BufferStore(
            db,
            object : MessageNotifier {
                override suspend fun onIncoming(
                    networkId: Long,
                    bufferId: Long,
                    type: BufferType,
                    hasMention: Boolean,
                    message: io.github.trevarj.motd.irc.event.IrcEvent.ChatMessage,
                ) = Unit

                override suspend fun onRoomsMerged(winnerId: Long, loserId: Long) {
                    retirements += winnerId to loserId
                }
            },
        )
        val winner = notifyingStore.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        val loser = notifyingStore.getOrCreate(networkId, "bob", "Bob", BufferType.QUERY)

        val failed = runCatching {
            db.withTransaction {
                notifyingStore.mergeRooms(winner.id, loser.id)
                error("abort outer transaction")
            }
        }

        assertTrue(failed.isFailure)
        assertTrue(retirements.isEmpty())
        assertEquals(loser.id, db.bufferDao().observeById(loser.id)?.id)
        assertTrue(
            db.appStateDao().keysLike("$ROOM_MERGE_PRESENTATION_PREFIX%").isEmpty(),
        )

        db.withTransaction { notifyingStore.mergeRooms(winner.id, loser.id) }
        assertTrue(retirements.isEmpty())
        notifyingStore.drainCommittedRoomMerges()
        assertEquals(listOf(winner.id to loser.id), retirements)
    }

    @Test
    fun uncertainRoomsDoNotMerge_butNickEvidenceMergesOldestAndLeavesRedirect() = runTest {
        val old = store.getOrCreate(networkId, "oldnick", "OldNick", BufferType.QUERY)
        val next = store.getOrCreate(networkId, "newnick", "NewNick", BufferType.QUERY)
        db.bufferDao().update(
            old.copy(
                pinned = true,
                historyComplete = true,
                readMarkerTime = 10,
                localReadAnchorTime = 100,
                localReadAnchorEventId = 5,
            ),
        )
        db.bufferDao().update(
            next.copy(
                muted = true,
                historyComplete = false,
                readMarkerTime = 20,
                localReadAnchorTime = 100,
                localReadAnchorEventId = 6,
            ),
        )
        db.composerDraftDao().upsert(
            io.github.trevarj.motd.data.db.ComposerDraftEntity(old.id, "older", 40, 1_000),
        )
        db.composerDraftDao().upsert(
            io.github.trevarj.motd.data.db.ComposerDraftEntity(next.id, "newer", 41, 2_000),
        )
        db.historyCursorDao().upsert(
            HistoryCursorEntity(
                roomId = old.id,
                newestMsgid = "old-newest",
                newestServerTime = 200,
                oldestMsgid = "old-oldest",
                oldestServerTime = 100,
                historyComplete = true,
            ),
        )
        db.historyCursorDao().upsert(
            HistoryCursorEntity(
                roomId = next.id,
                newestMsgid = "next-newest",
                newestServerTime = 500,
                oldestMsgid = "next-oldest",
                oldestServerTime = 300,
                historyComplete = false,
            ),
        )

        assertNotEquals(old.id, next.id)
        val merged = checkNotNull(
            store.bindNickChange(networkId, "oldnick", "newnick", "NewNick"),
        )

        assertEquals(minOf(old.id, next.id), merged.id)
        assertEquals("NewNick", merged.displayName)
        assertTrue(merged.pinned)
        assertTrue(merged.muted)
        assertEquals(20L, merged.readMarkerTime)
        assertEquals(100L, merged.localReadAnchorTime)
        assertEquals(5L, merged.localReadAnchorEventId)
        assertEquals("newer", db.composerDraftDao().byRoom(merged.id)?.text)
        assertEquals(41L, db.composerDraftDao().byRoom(merged.id)?.replyToEventId)
        assertFalse(merged.historyComplete)
        assertEquals(merged.id, db.bufferDao().observeById(next.id)?.id)
        assertEquals(listOf(merged.id), db.bufferDao().observeChatList().first().map { it.bufferId })
        val cursor = db.historyCursorDao().byRoom(merged.id)
        assertEquals("next-newest", cursor?.newestMsgid)
        assertEquals(500L, cursor?.newestServerTime)
        assertEquals("old-oldest", cursor?.oldestMsgid)
        assertEquals(100L, cursor?.oldestServerTime)
        assertFalse(cursor?.historyComplete ?: true)
        assertEquals(null, db.historyCursorDao().byRoom(if (merged.id == old.id) next.id else old.id))
    }

    @Test
    fun losingRoomIdResolvesForMessageRepositoryAndHistoryMediator() = runTest {
        val winner = store.getOrCreate(networkId, "old", "Old", BufferType.QUERY)
        val loser = store.getOrCreate(networkId, "new", "New", BufferType.QUERY)
        val eventId = db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    bufferId = loser.id,
                    msgid = "redirected-message",
                    serverTime = 1_000,
                    sender = "new",
                    kind = MessageKind.PRIVMSG,
                    text = "still visible",
                    dedupKey = "redirected-message",
                ),
            ),
        ).single()
        val merged = store.mergeRooms(winner.id, loser.id)
        var mediatorRoomId: Long? = null
        val repository = MessageRepositoryImpl(
            db.bufferDao(),
            db.networkIdentityDao(),
            db.messageDao(),
            db.reactionDao(),
            ChatHistoryMediatorFactory { roomId ->
                mediatorRoomId = roomId
                object : RemoteMediator<Int, MessageEntity>() {
                    override suspend fun load(
                        loadType: LoadType,
                        state: PagingState<Int, MessageEntity>,
                    ) = MediatorResult.Success(endOfPaginationReached = true)
                }
            },
        )

        assertEquals(eventId, repository.byMsgid(loser.id, "redirected-message")?.id)
        repository.messages(loser.id, io.github.trevarj.motd.data.visibility.MessageVisibilitySpec()).first()
        assertEquals(merged.id, mediatorRoomId)
    }

    @Test
    fun mergeTransfersLoserLayoutOverrideOnlyWhenWinnerInherits() = runTest {
        val winner = store.getOrCreate(networkId, "older", "Older", BufferType.QUERY)
        val loser = store.getOrCreate(networkId, "newer", "Newer", BufferType.QUERY)
        db.bufferDao().update(loser.copy(layoutDensityOverride = LayoutDensity.COMPACT))

        val merged = store.mergeRooms(winner.id, loser.id)

        assertEquals(LayoutDensity.COMPACT, merged.layoutDensityOverride)
        assertEquals(LayoutDensity.COMPACT, db.bufferDao().observeById(winner.id)?.layoutDensityOverride)
    }

    @Test
    fun mergeRetainsCanonicalWinnersConflictingLayoutOverride() = runTest {
        val winner = store.getOrCreate(networkId, "older", "Older", BufferType.QUERY)
        val loser = store.getOrCreate(networkId, "newer", "Newer", BufferType.QUERY)
        db.bufferDao().update(winner.copy(layoutDensityOverride = LayoutDensity.TWO_LINE))
        db.bufferDao().update(loser.copy(layoutDensityOverride = LayoutDensity.COMPACT))

        val merged = store.mergeRooms(winner.id, loser.id)

        assertEquals(LayoutDensity.TWO_LINE, merged.layoutDensityOverride)
    }

    @Test
    fun layoutOverrideWriteThroughRedirectUpdatesOnlyCanonicalRoom() = runTest {
        val winner = store.getOrCreate(networkId, "older", "Older", BufferType.QUERY)
        val loser = store.getOrCreate(networkId, "newer", "Newer", BufferType.QUERY)
        store.mergeRooms(winner.id, loser.id)

        assertEquals(1, db.bufferDao().setLayoutDensityOverride(loser.id, LayoutDensity.COMPACT))
        assertEquals(LayoutDensity.COMPACT, db.bufferDao().rawById(winner.id)?.layoutDensityOverride)
        assertEquals(null, db.bufferDao().rawById(loser.id)?.layoutDensityOverride)
        assertEquals(1, db.bufferDao().setLayoutDensityOverride(winner.id, null))
        assertEquals(null, db.bufferDao().rawById(winner.id)?.layoutDensityOverride)
    }

    @Test
    fun layoutOverridesAreIsolatedAcrossBufferKindsAndCanBeReset() = runTest {
        val channel = store.getOrCreate(networkId, "#room", "#room", BufferType.CHANNEL)
        val query = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        val server = store.getOrCreate(networkId, "server", "server", BufferType.SERVER)

        assertEquals(1, db.bufferDao().setLayoutDensityOverride(channel.id, LayoutDensity.COMPACT))
        assertEquals(1, db.bufferDao().setLayoutDensityOverride(query.id, LayoutDensity.TWO_LINE))

        assertEquals(LayoutDensity.COMPACT, db.bufferDao().rawById(channel.id)?.layoutDensityOverride)
        assertEquals(LayoutDensity.TWO_LINE, db.bufferDao().rawById(query.id)?.layoutDensityOverride)
        assertEquals(null, db.bufferDao().rawById(server.id)?.layoutDensityOverride)

        assertEquals(1, db.bufferDao().setLayoutDensityOverride(server.id, LayoutDensity.COMFORTABLE))
        assertEquals(LayoutDensity.COMFORTABLE, db.bufferDao().rawById(server.id)?.layoutDensityOverride)

        assertEquals(1, db.bufferDao().setLayoutDensityOverride(channel.id, null))
        assertEquals(null, db.bufferDao().rawById(channel.id)?.layoutDensityOverride)
        assertEquals(LayoutDensity.TWO_LINE, db.bufferDao().rawById(query.id)?.layoutDensityOverride)
        assertEquals(1, db.bufferDao().setLayoutDensityOverride(server.id, null))
        assertEquals(null, db.bufferDao().rawById(server.id)?.layoutDensityOverride)
    }

    @Test
    fun deletedChannelDoesNotGiveItsLayoutOverrideToARecreatedChannel() = runTest {
        val original = store.getOrCreate(networkId, "#room", "#room", BufferType.CHANNEL)
        db.bufferDao().setLayoutDensityOverride(original.id, LayoutDensity.COMPACT)

        db.bufferDao().deleteBuffer(original.id)
        val recreated = store.getOrCreate(networkId, "#room", "#room", BufferType.CHANNEL)

        assertEquals(null, recreated.layoutDensityOverride)
    }

    @Test
    fun dismissingCanonicalQueryPreservesRedirectsAndNickIdentity() = runTest {
        val old = store.getOrCreate(networkId, "oldnick", "OldNick", BufferType.QUERY)
        val next = store.getOrCreate(networkId, "newnick", "NewNick", BufferType.QUERY)
        db.bufferDao().setLayoutDensityOverride(old.id, LayoutDensity.COMPACT)
        val merged = checkNotNull(
            store.bindNickChange(networkId, "oldnick", "newnick", "NewNick"),
        )
        val losingId = if (merged.id == old.id) next.id else old.id

        db.bufferDao().deleteBuffer(merged.id)

        assertEquals(merged.id, db.bufferDao().canonicalId(losingId))
        assertTrue(db.bufferDao().rawById(merged.id)!!.dismissed)
        val resolved = store.getOrCreate(networkId, "newnick", "NewNick", BufferType.QUERY)
        assertEquals(merged.id, resolved.id)
        assertEquals(LayoutDensity.COMPACT, resolved.layoutDensityOverride)
    }
}
