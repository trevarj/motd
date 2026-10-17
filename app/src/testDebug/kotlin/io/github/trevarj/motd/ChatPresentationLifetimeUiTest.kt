package io.github.trevarj.motd

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.ChatPageCache
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.repo.ViewportRefreshAnchor
import io.github.trevarj.motd.data.repo.entryAnchorPagingKey
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.ui.chat.CHAT_ENTRY_VEIL_TAG
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatForegroundLifecycleGate
import io.github.trevarj.motd.ui.chat.ChatPagingGeneration
import io.github.trevarj.motd.ui.chat.ChatPositionTarget
import io.github.trevarj.motd.ui.chat.ChatPresentation
import io.github.trevarj.motd.ui.chat.ChatPresentationHost
import io.github.trevarj.motd.ui.chat.ChatPresentationRegistry
import io.github.trevarj.motd.ui.chat.ChatScrollPositionStore
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ChatViewModel
import io.github.trevarj.motd.ui.chat.ChatViewModelTest
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.nav.ChatListRoute
import io.github.trevarj.motd.ui.nav.ChatRoute
import io.github.trevarj.motd.ui.nav.openChat
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatPresentationLifetimeUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun backAndAlternatingRoomsKeepNativeItemsAndViewportWithoutAnEmptyReentry() =
        withFixture { fixture ->
            val firstRoom = fixture.rooms.first().id
            val otherRoom = fixture.rooms.last().id
            fixture.open(firstRoom)
            val first = fixture.awaitReady()
            assertEquals(186, first.presentation.listState.firstVisibleItemIndex)
            assertEquals(71, first.presentation.listState.firstVisibleItemScrollOffset)
            fixture.back()
            fixture.open(otherRoom)
            val other = fixture.awaitReady()
            fixture.back()
            fixture.open(firstRoom)
            val reopened = fixture.awaitReady()
            assertSame(first.presentation, reopened.presentation)
            assertSame(first.presentation.items, reopened.presentation.items)
            assertSame(first.presentation.listState, reopened.presentation.listState)
            assertEquals(600, reopened.firstLoadedCount)
            assertEquals("the destination's first composition must not reset the saved viewport", 186, reopened.firstIndex)
            assertEquals(71, reopened.firstOffset)
            assertEquals(first.expectedId, reopened.measuredId)
            fixture.back()
            fixture.open(otherRoom)
            val otherAgain = fixture.awaitReady()
            assertSame(other.presentation, otherAgain.presentation)
            assertTrue(otherAgain.firstCount > 0)
            assertEquals(0, otherAgain.firstIndex)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                otherAgain.reads > 0
            }
            assertTrue("the resumed bottom visit must eventually acknowledge its measured rows", otherAgain.reads > 0)
        }

    @Test
    fun retainedRowsRemainInaccessibleUntilFreshVisitTargetIsReady() =
        withFixture { fixture ->
            val room = fixture.rooms.first().id
            fixture.open(room)
            val original = fixture.awaitReady()
            fixture.back()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { fixture.holdTarget = true }
            fixture.open(room)
            val pending = fixture.awaitVisit()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertSame(original.presentation, pending.presentation)
            assertEquals(186, pending.firstIndex)
            assertTrue(pending.firstCount > 0)
            assertFalse(pending.ready)
            assertEquals(0, pending.reads)
            assertEquals(0, pending.saves)
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertIsDisplayed()
            compose.onAllNodesWithText(fixture.expectedText(room)).assertCountEquals(0)
            compose.runOnIdle { fixture.holdTarget = false }
            val ready = fixture.awaitReady()
            assertEquals(original.expectedId, ready.measuredId)
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertDoesNotExist()
            compose.onNodeWithTag("chat_message_${fixture.expectedMsgid(room)}", useUnmergedTree = true).assertIsDisplayed()
            compose.onAllNodesWithText(fixture.expectedText(room)).assertCountEquals(1)
        }

    @Test
    fun changedFreshUnreadTargetIsPositionedBeforeTheRetainedTimelineIsExposed() =
        withFixture { fixture ->
            val room = fixture.rooms.first().id
            fixture.open(room)
            val previous = fixture.awaitReady()
            fixture.back()
            compose.runOnIdle {
                fixture.plans[room] = fixture.target(room, 450).copy(placeAtTop = true, forceScrollOnEntry = true)
            }
            fixture.open(room)
            val changed = fixture.awaitReady()
            assertSame(previous.presentation, changed.presentation)
            assertEquals(186, changed.firstIndex)
            assertEquals(fixture.plans.getValue(room).expectedEventId, changed.measuredId)
            val layout = changed.presentation.listState.layoutInfo
            val row = layout.visibleItemsInfo.first { it.key == changed.measuredId }
            assertTrue("fresh unread must already be at the viewport top on admission", kotlin.math.abs(layout.viewportEndOffset - row.offset - row.size) <= 1)
            assertEquals(0, changed.reads)
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertDoesNotExist()
            compose.onNodeWithTag("chat_message_${fixture.expectedMsgid(room)}", useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun valueIdenticalRefreshCertifiesTheStructurallyRetainedComposeSnapshot() =
        withFixture { fixture ->
            val room = fixture.rooms.first().id
            fixture.open(room)
            val visit = fixture.awaitReady()
            val snapshot = visit.presentation.items.itemSnapshotList
            val version = checkNotNull(visit.lease.entry.presentedVersion)
            fixture.insertHiddenPresence(room)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                visit.lease.entry.presentedVersion != version &&
                    visit.presentation.items.loadState.source.refresh is LoadState.NotLoading
            }
            compose.runOnIdle {
                assertSame("Paging Compose intentionally suppresses an equal snapshot assignment", snapshot, visit.presentation.items.itemSnapshotList)
                assertEquals(900, snapshot.size)
                assertEquals(600, snapshot.items.size)
                assertTrue(visit.lease.entry.eligible)
            }
            fixture.back()
            fixture.open(room)
            val again = fixture.awaitReady()
            assertSame(visit.presentation, again.presentation)
            assertEquals(186, again.firstIndex)
            assertEquals(visit.expectedId, again.measuredId)
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertDoesNotExist()
        }

    @Test
    fun coveredVisitRestoresItsOwnViewportAfterAnotherVisitMovesTheSharedPresentation() =
        withFixture { fixture ->
            val room = fixture.rooms.first().id
            fixture.open(room)
            val original = fixture.awaitReady()
            fixture.push(fixture.rooms.last().id)
            fixture.awaitReady()
            compose.runOnIdle { fixture.plans[room] = fixture.target(room, 400).copy(offset = 43) }
            fixture.push(room)
            val newer = fixture.awaitReady()
            assertSame(original.presentation, newer.presentation)
            assertEquals(400, newer.presentation.listState.firstVisibleItemIndex)
            fixture.popCovered()
            fixture.awaitReady()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { fixture.holdTarget = true }
            fixture.popCovered()
            val returning = fixture.awaitVisit()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals(original.entryId, returning.entryId)
            assertSame(original.presentation, returning.presentation)
            assertEquals(400, returning.firstIndex)
            assertFalse("the old visit's Settled latch must not authorize the newer viewport", returning.ready)
            assertEquals(0, returning.reads)
            assertEquals(0, returning.saves)
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertIsDisplayed()
            compose.runOnIdle { fixture.holdTarget = false }
            val restored = fixture.awaitReady()
            assertEquals(original.expectedId, restored.measuredId)
            assertEquals(186, restored.presentation.listState.firstVisibleItemIndex)
            assertEquals(71, restored.presentation.listState.firstVisibleItemScrollOffset)
        }

    @Test
    fun stalledRealEntryPreparationRevealsConfirmedViewportAtTimeoutWithoutLateAutomaticJumpOrPrematureRead() =
        withFixture { fixture ->
            val room = fixture.rooms.first().id
            fixture.open(room)
            val original = fixture.awaitReady()
            fixture.back()
            fixture.prepareHeldReturn(room)
            compose.mainClock.autoAdvance = false
            fixture.open(room)
            val returning = fixture.awaitVisit()
            val owner = checkNotNull(returning.owner)
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                fixture.heldMessages.entered.isCompleted
            }
            assertSame(original.presentation, returning.presentation)
            assertEquals(600, returning.firstLoadedCount)
            assertEquals(186, returning.firstIndex)
            assertEquals(71, returning.firstOffset)
            assertFalse(owner.entryPreparationReady.value)
            assertEquals(0, returning.reads)
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertIsDisplayed()
            compose.onAllNodesWithText(fixture.expectedText(room)).assertCountEquals(0)

            compose.mainClock.advanceTimeBy(1_600)
            fixture.awaitReady()
            assertTrue(owner.entryFallbackLatched.value)
            assertFalse(owner.entryPreparationReady.value)
            assertEquals(0, returning.reads)
            assertEquals(original.expectedId, returning.measuredId)
            assertEquals(186, returning.presentation.listState.firstVisibleItemIndex)
            assertEquals(71, returning.presentation.listState.firstVisibleItemScrollOffset)
            compose.onAllNodesWithText(fixture.expectedText(room)).assertCountEquals(1)

            fixture.heldMessages.release()
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                owner.entryPreparationReady.value
            }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            assertTrue(owner.initialTarget.value == null)
            assertEquals("late unread target 450 must not move the selected fallback", 186, returning.presentation.listState.firstVisibleItemIndex)
            assertEquals(71, returning.presentation.listState.firstVisibleItemScrollOffset)

            // A later explicit user jump is not the retired automatic return target.
            compose.runOnIdle { owner.focusRecentMention(fixture.target(room, 400).copy(fromSavedPosition = false)) }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                returning.presentation.listState.firstVisibleItemIndex == 400 && owner.jumpTarget.value == null
            }
            val readsBeforeUser = returning.reads
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("chat_scroll_to_bottom_fab").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                returning.presentation.listState.firstVisibleItemIndex == 0 &&
                    returning.presentation.listState.firstVisibleItemScrollOffset == 0 &&
                    !returning.presentation.listState.isScrollInProgress && returning.reads > readsBeforeUser
            }
        }

    @Test
    fun stalledRealEntryPreparationNeverRevealsCommittedEditedPayloadFromRetainedWindow() =
        withFixture { fixture ->
            val room = fixture.rooms.first().id
            fixture.open(room)
            val original = fixture.awaitReady()
            val oldSnapshot = original.presentation.items.itemSnapshotList
            val replacementText = body("freshly certified replacement")
            fixture.back()
            // Deliberately commit before Room's observer runs: the retained old public snapshot exists.
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET text = ? WHERE id = ?",
                arrayOf<Any>(replacementText, checkNotNull(original.expectedId)),
            )
            fixture.prepareHeldReturn(room, holdNativeRefresh = true)
            compose.mainClock.autoAdvance = false
            fixture.open(room)
            val returning = fixture.awaitVisit()
            assertSame(original.presentation, returning.presentation)
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                fixture.nativeLoads.blocked.isCompleted && returning.presentation.items.loadState.source.refresh is LoadState.Loading
            }
            assertSame(oldSnapshot, returning.presentation.items.itemSnapshotList)
            assertEquals(
                fixture.expectedText(room),
                returning.presentation.items
                    .peek(186)
                    ?.text,
            )
            compose.onAllNodesWithText(fixture.expectedText(room)).assertCountEquals(0)
            compose.mainClock.advanceTimeBy(1_600)
            compose.waitForIdle()
            assertSame("the real old native window must still exist across timeout", oldSnapshot, returning.presentation.items.itemSnapshotList)
            assertFalse(returning.ready)
            assertFalse(checkNotNull(returning.owner).entryFallbackLatched.value)
            assertEquals(0, returning.reads)
            compose.onAllNodesWithText(fixture.expectedText(room)).assertCountEquals(0)
            compose.onAllNodesWithText(replacementText).assertCountEquals(0)
            fixture.nativeLoads.resume()
            fixture.awaitReady()
            assertTrue(checkNotNull(returning.owner).entryFallbackLatched.value)
            assertFalse(checkNotNull(returning.owner).entryPreparationReady.value)
            assertEquals(0, returning.reads)
            compose.onAllNodesWithText(fixture.expectedText(room)).assertCountEquals(0)
            compose.onAllNodesWithText(replacementText).assertCountEquals(1)
        }

    private fun body(msgid: String): String = "$msgid\nsecond line\nthird line\nfourth line"

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        var bodyFailure: Throwable? = null
        try {
            fixture.install()
            block(fixture)
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            try {
                compose.mainClock.autoAdvance = true
                compose.runOnIdle { fixture.unmount() }
                compose.waitForIdle()
                compose.runOnIdle {
                    fixture.clearOwners()
                    fixture.scope.cancel()
                }
                compose.waitUntil(10_000) {
                    compose.waitForIdle()
                    checkNotNull(fixture.scope.coroutineContext[Job]).isCompleted && fixture.ownerJobs.all { it.isCompleted }
                }
            } catch (cleanupFailure: Throwable) {
                val original = bodyFailure
                if (original == null) throw cleanupFailure
                if (cleanupFailure !== original) original.addSuppressed(cleanupFailure)
            } finally {
                fixture.db.close()
            }
        }
    }

    private class Visit(
        val entryId: String,
        val presentation: ChatPresentation,
        val expectedId: Long?,
        val expectedMsgid: String?,
        val lease: ChatPageCache.Lease,
        val owner: ChatViewModel? = null,
    ) {
        val firstCount = presentation.items.itemCount
        val firstLoadedCount = presentation.items.itemSnapshotList.items.size
        val firstIndex = presentation.listState.firstVisibleItemIndex
        val firstOffset = presentation.listState.firstVisibleItemScrollOffset
        var ready by mutableStateOf(false)
        var measuredId: Long? = null
        var reads = 0
        var saves = 0
    }

    private class HeldEntryMessages(
        private val delegate: MessageRepository,
    ) : MessageRepository by delegate {
        @Volatile
        private var gate: CompletableDeferred<Unit>? = null
        var entered = CompletableDeferred<Unit>()
            private set

        fun hold() {
            entered = CompletableDeferred()
            gate = CompletableDeferred()
        }

        fun release() {
            val waiting = gate
            gate = null
            waiting?.complete(Unit)
        }

        override suspend fun countNewerThan(
            bufferId: Long,
            serverTime: Long,
            id: Long,
            visibility: MessageVisibilitySpec,
        ): Int {
            gate?.let {
                entered.complete(Unit)
                it.await()
            }
            return delegate.countNewerThan(bufferId, serverTime, id, visibility)
        }
    }

    private class HoldingDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        private var paused = false
        val blocked = CompletableDeferred<Unit>()

        fun pause() {
            synchronized(pending) { paused = true }
        }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            val held =
                synchronized(pending) {
                    if (paused) {
                        pending.addLast(context to block)
                        true
                    } else {
                        false
                    }
                }
            if (held) blocked.complete(Unit) else Dispatchers.IO.dispatch(context, block)
        }

        fun resume() {
            val queued =
                synchronized(pending) {
                    paused = false
                    pending.toList().also { pending.clear() }
                }
            for ((context, block) in queued) Dispatchers.IO.dispatch(context, block)
        }
    }

    private inner class Fixture {
        val db = inMemoryDb(directCommit = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val rooms = ArrayList<BufferEntity>()
        private val ids = LinkedHashMap<Long, List<Long>>()
        val plans = LinkedHashMap<Long, ChatPositionTarget>()
        private val visits = ArrayList<Visit>()
        private var openingVisit = 0
        private lateinit var nav: NavHostController
        var holdTarget by mutableStateOf(false)
        private val cache: ChatPageCache
        private val scrollPositions = ChatScrollPositionStore()
        private val ownerStores = LinkedHashSet<androidx.lifecycle.ViewModelStore>()
        val ownerJobs = ArrayList<Job>()
        private var mounted by mutableStateOf(true)
        private var realOwners by mutableStateOf(false)
        private var holdNativeRefresh = false
        val nativeLoads = HoldingDispatcher()
        val heldMessages: HeldEntryMessages

        init {
            runBlocking {
                val networkId = db.networkDao().insert(network())
                repeat(2) { ordinal ->
                    val room = buffer(networkId, "#retained-$ordinal").copy(joined = true)
                    val roomId = db.bufferDao().insert(room)
                    rooms += room.copy(id = roomId)
                    ids[roomId] =
                        db.messageDao().insertAll(
                            (1..900).map { index ->
                                message(
                                    roomId,
                                    body("retained-$ordinal-$index"),
                                    serverTime = index.toLong(),
                                    msgid = "retained-$ordinal-$index",
                                    dedupKey = "retained-$ordinal-$index",
                                )
                            },
                        )
                }
            }
            val repository =
                MessageRepositoryImpl(
                    db.bufferDao(),
                    db.networkIdentityDao(),
                    db,
                    db.reactionDao(),
                    ChatHistoryMediatorFactory { _, _, _ ->
                        object : RemoteMediator<Int, MessageEntity>() {
                            override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

                            override suspend fun load(
                                loadType: LoadType,
                                state: PagingState<Int, MessageEntity>,
                            ) = MediatorResult.Success(true)
                        }
                    },
                    db.historyGapDao(),
                    ioDispatcher = nativeLoads,
                )
            cache = ChatPageCache(repository, db)
            heldMessages = HeldEntryMessages(repository)
            cache.start(
                scope,
                object : AppVisibility {
                    override val onScreen = MutableStateFlow(true)
                },
                MutableStateFlow(Settings()),
            )
            plans[rooms.first().id] = target(rooms.first().id, 186).copy(offset = 71)
            plans[rooms.last().id] = target(rooms.last().id, 0)
        }

        fun prepareHeldReturn(
            roomId: Long,
            holdNativeRefresh: Boolean = false,
        ) {
            this.holdNativeRefresh = holdNativeRefresh
            val room = rooms.first { it.id == roomId }
            checkNotNull(scrollPositions.get(roomId))
            runBlocking {
                db.bufferDao().update(room.copy(localReadAnchorTime = 449, localReadAnchorEventId = ids.getValue(roomId)[448]))
            }
            heldMessages.hold()
            compose.runOnIdle { realOwners = true }
        }

        fun unmount() {
            nativeLoads.resume()
            heldMessages.release()
            mounted = false
        }

        fun clearOwners() {
            ownerStores.forEach { it.clear() }
        }

        fun insertHiddenPresence(room: Long) {
            runBlocking {
                db.messageDao().insertAll(
                    listOf(
                        message(
                            room,
                            "a peer who never spoke joined",
                            sender = "quiet-peer",
                            kind = MessageKind.JOIN,
                            serverTime = 2_000,
                            dedupKey = "hidden-presence",
                        ),
                    ),
                )
            }
        }

        fun target(
            room: Long,
            index: Int,
        ): ChatPositionTarget =
            ChatPositionTarget(
                index = index,
                expectedEventId = ids.getValue(room)[899 - index],
                expectedMsgid = "retained-${rooms.indexOfFirst { it.id == room }}-${900 - index}",
                serverTime = (900 - index).toLong(),
                fromSavedPosition = true,
            )

        fun expectedMsgid(room: Long): String = checkNotNull(plans.getValue(room).expectedMsgid)

        fun expectedText(room: Long): String = body(expectedMsgid(room))

        fun install() {
            compose.setContent {
                if (!mounted) return@setContent
                val registry = remember { ChatPresentationRegistry() }
                val controller = rememberNavController()
                SideEffect { nav = controller }
                ChatPresentationHost(cache, registry)
                MotdTheme(dynamicColor = false) {
                    NavHost(controller, startDestination = ChatListRoute) {
                        composable<ChatListRoute> {
                            Column {
                                for (room in rooms) {
                                    Button(
                                        onClick = { controller.openChat(ChatRoute(room.id), replaceCurrentChat = false) },
                                        modifier = Modifier.testTag("open-${room.id}"),
                                    ) { Text(room.displayName) }
                                }
                            }
                        }
                        composable<ChatRoute> { entry ->
                            if (realOwners) {
                                RealDestination(entry, entry.toRoute<ChatRoute>().bufferId, registry)
                            } else {
                                Destination(entry, entry.toRoute<ChatRoute>().bufferId, registry)
                            }
                        }
                    }
                }
            }
        }

        @Composable
        private fun Destination(
            entry: NavBackStackEntry,
            roomId: Long,
            registry: ChatPresentationRegistry,
        ) {
            val entryId = entry.id
            val desired =
                remember(entry) {
                    val savedIndex = entry.savedStateHandle.get<Int>("fixture_viewport_index")
                    if (savedIndex == null) {
                        plans.getValue(roomId).also {
                            entry.savedStateHandle["fixture_viewport_index"] = it.index
                            entry.savedStateHandle["fixture_viewport_offset"] = it.offset
                            entry.savedStateHandle["fixture_viewport_top"] = it.placeAtTop
                        }
                    } else {
                        target(roomId, savedIndex).copy(
                            offset = entry.savedStateHandle.get<Int>("fixture_viewport_offset") ?: 0,
                            placeAtTop = entry.savedStateHandle.get<Boolean>("fixture_viewport_top") == true,
                        )
                    }
                }
            val lease by produceState<ChatPageCache.Lease?>(null, entryId) {
                val acquired =
                    cache.acquire(
                        roomId,
                        MessageVisibilitySpec(),
                        entryAnchorPagingKey(desired.index),
                        desired.index,
                        desired.expectedEventId,
                        desired.expectedEventId?.let(ViewportRefreshAnchor::Parked),
                    )
                value = acquired
                try {
                    awaitCancellation()
                } finally {
                    acquired.release()
                }
            }
            val current = lease ?: return
            val revision by current.changes.collectAsState()
            val presentation = registry.find(current) ?: return
            val visit =
                remember(entryId, current) {
                    Visit(entryId, presentation, desired.expectedEventId, desired.expectedMsgid, current).also(visits::add)
                }
            var pending by remember(entryId) { mutableStateOf<ChatPositionTarget?>(if (holdTarget) null else desired) }
            var entryState by remember(entryId) {
                mutableStateOf<EntryPositionState>(
                    if (entry.savedStateHandle.get<Boolean>("fixture_settled") == true) EntryPositionState.Settled else EntryPositionState.Pending,
                )
            }
            LaunchedEffect(holdTarget) {
                if (!holdTarget && !visit.ready) pending = desired
            }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            var resumed by remember(lifecycle) { mutableStateOf(false) }
            DisposableEffect(lifecycle) {
                val gate = ChatForegroundLifecycleGate(onResume = { resumed = true }, onPause = { resumed = false })
                val observer = LifecycleEventObserver { _, event -> gate.onEvent(event) }
                lifecycle.addObserver(observer)
                gate.sync(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                onDispose {
                    lifecycle.removeObserver(observer)
                    gate.dispose()
                }
            }
            ChatContent(
                state = ChatState(buffer = rooms.first { it.id == roomId }, connState = IrcClientState.Ready("me", emptySet(), emptyMap())),
                items = presentation.items,
                presentation = presentation,
                presentationLease = current,
                presentationRevision = revision,
                presentedVisibilitySpec = MessageVisibilitySpec(),
                composerEnabled = false,
                onBack = { nav.popBackStack() },
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
                showImages = false,
                showLinkPreviews = false,
                chatWallpaper = WallpaperSelection(ChatWallpaperPreset.NONE),
                initialTarget = pending,
                entryState = entryState,
                viewportReadEnabled = resumed,
                rawNewestAnchor = TimelineAnchor(900, ids.getValue(roomId).last()),
                onInitialPositionHandled = {
                    val measured =
                        presentation.listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == desired.expectedEventId }
                    assertTrue("entry cannot complete before its actual row is measured", measured != null)
                    visit.measuredId = measured?.key as? Long
                    visit.ready = true
                    pending = null
                    entryState = EntryPositionState.Settled
                    entry.savedStateHandle["fixture_settled"] = true
                },
                onMarkRead = {
                    assertTrue("retained rows cannot acknowledge before fresh placement", visit.ready)
                    assertTrue("retention is not destination RESUMED", resumed)
                    visit.reads++
                },
                onScrollPositionChanged = {
                    assertTrue("uncertified rows cannot overwrite a saved viewport", visit.ready)
                    visit.saves++
                    scrollPositions.put(roomId, it)
                    entry.savedStateHandle["fixture_viewport_index"] = it.index
                    entry.savedStateHandle["fixture_viewport_offset"] = it.offset
                    entry.savedStateHandle["fixture_viewport_top"] = false
                },
                onClearScrollPosition = {
                    assertTrue(visit.ready)
                    visit.saves++
                    scrollPositions.markParkedAtBottom(roomId)
                    entry.savedStateHandle["fixture_viewport_index"] = 0
                    entry.savedStateHandle["fixture_viewport_offset"] = 0
                    entry.savedStateHandle["fixture_viewport_top"] = false
                },
                onViewportRefreshAnchor = current::updateViewport,
            )
        }

        @Composable
        private fun RealDestination(
            entry: NavBackStackEntry,
            roomId: Long,
            registry: ChatPresentationRegistry,
        ) {
            val owner =
                viewModel<ChatViewModel>(viewModelStoreOwner = entry) {
                    val room = rooms.first { it.id == roomId }
                    ChatViewModelTest
                        .externalTranslationFixture(
                            db = db,
                            buffer = room,
                            scope = scope,
                            savedStateHandle = entry.savedStateHandle,
                            buffers = ChatViewModelTest.roomBuffers(db),
                            connectionStates = mapOf(room.networkId to IrcClientState.Ready("me", emptySet(), emptyMap())),
                            messages = heldMessages,
                            scrollPositions = scrollPositions,
                        ).also {
                            ownerStores += entry.viewModelStore
                            ownerJobs += checkNotNull(it.viewModelScope.coroutineContext[Job])
                        }
                }
            val state by owner.state.collectAsStateWithLifecycle()
            val initialTarget by owner.initialTarget.collectAsStateWithLifecycle()
            val jumpTarget by owner.jumpTarget.collectAsStateWithLifecycle()
            val entryState by owner.entryState.collectAsStateWithLifecycle()
            val preparationReady by owner.entryPreparationReady.collectAsStateWithLifecycle()
            val intent by owner.entryPositionIntent.collectAsStateWithLifecycle()
            val readHold by owner.viewportReadHold.collectAsStateWithLifecycle()
            val unread by owner.unreadEntrySnapshot.collectAsStateWithLifecycle()
            val readAnchor by owner.localReadAnchor.collectAsStateWithLifecycle()
            val newestAnchor by owner.rawNewestAnchor.collectAsStateWithLifecycle()
            val identity by owner.identityRules.collectAsStateWithLifecycle()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            var resumed by remember(lifecycle) { mutableStateOf(false) }
            DisposableEffect(lifecycle, owner) {
                val gate =
                    ChatForegroundLifecycleGate(
                        onResume = {
                            resumed = true
                            owner.onResume()
                        },
                        onPause = {
                            resumed = false
                            owner.onPause()
                        },
                    )
                val observer = LifecycleEventObserver { _, event -> gate.onEvent(event) }
                lifecycle.addObserver(observer)
                gate.sync(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                onDispose {
                    lifecycle.removeObserver(observer)
                    gate.dispose()
                }
            }
            val generation by produceState<ChatPagingGeneration?>(null, owner) {
                heldMessages.entered.await()
                owner.state.first { it.buffer?.id == roomId }
                if (holdNativeRefresh) nativeLoads.pause()
                owner.retainedMessageGenerations(cache).collect { value = it }
            }
            val current = generation ?: return
            val lease = current.lease ?: return
            val revision by lease.changes.collectAsState()
            val presentation = registry.find(lease) ?: return
            val desired = plans.getValue(roomId)
            val visit =
                remember(entry, lease) {
                    Visit(entry.id, presentation, desired.expectedEventId, desired.expectedMsgid, lease, owner).also(visits::add)
                }
            ChatContent(
                state = state,
                items = presentation.items,
                presentation = presentation,
                presentationLease = lease,
                presentationRevision = revision,
                presentedVisibilitySpec = current.spec,
                entryFallback = current.fallback,
                entryPreparationReady = preparationReady,
                entryPositionIntent = intent,
                canAdoptRetainedFallback = owner::canAdoptRetainedFallback,
                onAdoptRetainedFallback = { position, expectedIntent ->
                    owner.adoptRetainedFallback(position, expectedIntent).also { accepted ->
                        if (accepted) {
                            visit.measuredId = position.rowId
                            visit.ready = true
                        }
                    }
                },
                composerEnabled = false,
                onBack = { nav.popBackStack() },
                onOpenChannelInfo = {},
                onOpenSearch = {},
                onOpenImage = {},
                nickNormalizer = owner.nickNormalizer(),
                identityRules = identity,
                onSubmit = {},
                onTyping = {},
                onSetReply = {},
                onReact = { _, _ -> },
                onRetry = {},
                loadPreview = { _, _ -> null },
                showImages = false,
                showLinkPreviews = false,
                chatWallpaper = WallpaperSelection(ChatWallpaperPreset.NONE),
                initialTarget = initialTarget,
                jumpTarget = jumpTarget,
                entryState = entryState,
                unreadEntrySnapshot = unread,
                readMarkerLive = readAnchor,
                rawNewestAnchor = newestAnchor,
                viewportReadEnabled = resumed && preparationReady && !readHold,
                onInitialPositionHandled = {
                    visit.measuredId = initialTarget?.expectedEventId
                    visit.ready = true
                    owner.onInitialPositionHandled()
                },
                onMarkRead = {
                    assertTrue("metadata-stalled retained fallback cannot acknowledge rows", preparationReady)
                    assertTrue("retained fallback cannot acknowledge before resume", resumed)
                    visit.reads++
                    owner.markRead(it)
                },
                onJumpHandled = owner::onJumpHandled,
                onJumpToNewest = owner::jumpToNewest,
                onFocusRecentMention = owner::focusRecentMention,
                onJumpUnresolved = owner::onJumpUnresolved,
                onReresolveJump = owner::reresolveJumpOnce,
                onInitialPositionUnresolved = owner::onInitialPositionUnresolved,
                onReresolveInitial = owner::reresolveInitialOnce,
                onScrollPositionChanged = owner::saveScrollPosition,
                onClearScrollPosition = owner::clearScrollPosition,
                onForgetScrollPosition = owner::forgetScrollPosition,
                onTimelineInteraction = owner::onTimelineInteraction,
                onViewportRefreshAnchor = owner::reportViewportRefreshAnchor,
                onFurthestDisplayedChanged = owner::recordFurthestDisplayed,
            )
        }

        fun open(room: Long) {
            openingVisit = visits.size
            compose.onNodeWithTag("open-$room").performClick()
        }

        fun push(room: Long) {
            openingVisit = visits.size
            compose.runOnIdle { nav.openChat(ChatRoute(room), replaceCurrentChat = false) }
        }

        fun popCovered() {
            openingVisit = visits.size
            compose.runOnIdle { assertTrue(nav.popBackStack()) }
        }

        fun awaitVisit(): Visit {
            compose.waitUntil(10_000) {
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                visits.size > openingVisit
            }
            return visits.last()
        }

        fun awaitReady(): Visit {
            val visit = awaitVisit()
            compose.waitUntil(10_000) {
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                visit.ready && compose.onAllNodesWithTag(CHAT_ENTRY_VEIL_TAG).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("chat_message_${visit.expectedMsgid}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            return visit
        }

        fun back() {
            compose.runOnIdle { assertTrue(nav.popBackStack()) }
            compose.onNodeWithTag("open-${rooms.first().id}").assertIsDisplayed()
            compose.waitForIdle()
        }
    }
}
