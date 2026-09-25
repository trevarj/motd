package io.github.trevarj.motd

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.MESSAGE_PAGING_CONFIG
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.repo.ViewportRefreshAnchor
import io.github.trevarj.motd.data.repo.entryAnchorPagingKey
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatPositionTarget
import io.github.trevarj.motd.ui.chat.ChatScrollPosition
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ConversationPresenceState
import io.github.trevarj.motd.ui.chat.MessageList
import io.github.trevarj.motd.ui.chat.ReportMessageViewport
import io.github.trevarj.motd.ui.chat.isSystemRunChunkBoundary
import io.github.trevarj.motd.ui.chat.summarizeSystemRun
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class MessagePagingDemandUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun scrollingNewRoomTailsKeepsRequestingOlderPagesBeyondThePagingCache() {
        assertHistoryDemand(holdAppendResult = true)
    }

    @Test
    fun completingAppendBeforeRoomRefreshKeepsDemandAtTheVisibleTail() {
        assertHistoryDemand(holdAppendResult = false)
    }

    @Test
    fun roomJoinPartStormKeepsOrderedRunsExpandableAcrossInvalidations() {
        val db = inMemoryDb(directCommit = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val bufferId =
            runBlocking {
                val networkId = db.networkDao().insert(network())
                db.bufferDao().insert(buffer(networkId, "#storm"))
            }

        fun row(
            id: Long,
            kind: MessageKind,
            text: String,
        ) = MessageEntity(
            id = id,
            bufferId = bufferId,
            msgid = "storm-$id",
            serverTime = id * 1_000,
            sender = if (kind == MessageKind.PRIVMSG) "alice" else "peer",
            normalizedActor = if (kind == MessageKind.PRIVMSG) "alice" else "peer",
            kind = kind,
            text = text,
            dedupKey = "storm-$id",
            timelineOrder = id,
        )

        val events =
            (2L..193L).map { id ->
                val kind = if (id % 2 == 0L) MessageKind.JOIN else MessageKind.PART
                row(id, kind, "peer ${if (kind == MessageKind.JOIN) "joined" else "left"} #$id")
            }
        runBlocking { db.messageDao().insertAll(listOf(row(1, MessageKind.PRIVMSG, "Before storm"))) }
        val pages =
            Pager(MESSAGE_PAGING_CONFIG) {
                db.messageDao().pagingSource(
                    messagePagingQuery(bufferId, MessageVisibilitySpec(presenceMode = PresenceMode.ALL)),
                )
            }.flow.cachedIn(scope)
        lateinit var items: LazyPagingItems<MessageEntity>
        lateinit var listState: LazyListState

        try {
            compose.setContent {
                val collected = pages.collectAsLazyPagingItems(Dispatchers.Main.immediate)
                val timeline = rememberLazyListState()
                SideEffect {
                    items = collected
                    listState = timeline
                }
                MotdTheme(dynamicColor = false) {
                    MessageList(
                        items = collected,
                        listState = timeline,
                        networkId = 1,
                        bufferId = bufferId,
                        readMarkerTime = null,
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
                    )
                }
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                items.itemCount == 1
            }
            // Individual Room commits, interleaved with real LazyColumn frames, repeatedly replace
            // the Paging presentation while its visible JOIN/PART run grows.
            events.chunked(8).forEach { batch ->
                batch.forEach { event ->
                    runBlocking { db.messageDao().insertAll(listOf(event)) }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
            }
            runBlocking { db.messageDao().insertAll(listOf(row(194, MessageKind.PRIVMSG, "After storm"))) }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                items.itemCount == 194 && items.loadState.source.refresh is LoadState.NotLoading
            }
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
            compose.onNodeWithTag("chat_message_storm-194", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { assertEquals(195, listState.layoutInfo.totalItemsCount) }

            // The newest contiguous chunk is adjacent to the final chat message. Its boundary
            // follows persisted IDs rather than positions shifted by Room invalidations.
            val chunk =
                (193L downTo 2L)
                    .takeWhile { id -> id == 193L || !isSystemRunChunkBoundary(id) }
                    .map { id -> events[(id - 2).toInt()] }
            assertTrue("Fixture needs a collapsible JOIN/PART run", chunk.size > 1)
            val pill = hasTestTag("chat_system_pill") and hasAnyDescendant(hasText(summarizeSystemRun(chunk)))
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(1)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                runCatching { compose.onNode(pill, useUnmergedTree = true).assertIsDisplayed() }.isSuccess
            }
            compose.onNode(pill, useUnmergedTree = true).performClick()
            val bounds = mutableListOf<Rect>()
            chunk.asReversed().forEach { event ->
                val line = compose.onNodeWithText(event.text, useUnmergedTree = true).assertIsDisplayed()
                compose.onAllNodesWithText(event.text, useUnmergedTree = true).assertCountEquals(1)
                bounds += line.fetchSemanticsNode().boundsInRoot
            }
            assertTrue("Expanded JOIN/PART lines are not chronological", bounds.zipWithNext().all { (a, b) -> a.top < b.top })
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(193)
            compose.onNodeWithTag("chat_message_storm-1", useUnmergedTree = true).assertIsDisplayed()

            val expected =
                listOf(row(1, MessageKind.PRIVMSG, "Before storm")) +
                    events +
                    row(194, MessageKind.PRIVMSG, "After storm")
            assertEquals(
                expected.map { Triple(it.id, it.kind, it.text) },
                runBlocking { db.messageDao().historyRowsForMerge(bufferId) }
                    .map { Triple(it.id, it.kind, it.text) },
            )
        } finally {
            compose.runOnIdle { scope.cancel() }
            db.close()
        }
    }

    @Test
    fun collapsedPresenceStormKeepsNewestViewportLoadedWithoutStealingHistory() {
        val db = inMemoryDb(directCommit = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val bufferId =
            runBlocking {
                val networkId = db.networkDao().insert(network())
                db.bufferDao().insert(buffer(networkId, "#presence"))
            }

        fun row(
            id: Long,
            kind: MessageKind,
        ) = MessageEntity(
            id = id,
            bufferId = bufferId,
            msgid = "presence-$id",
            serverTime = id * 1_000,
            sender = if (kind == MessageKind.PRIVMSG) "alice" else "peer",
            normalizedActor = if (kind == MessageKind.PRIVMSG) "alice" else "peer",
            kind = kind,
            text = if (kind == MessageKind.PRIVMSG) "Chat $id" else "peer ${if (kind == MessageKind.JOIN) "joined" else "left"} #presence $id",
            dedupKey = "presence-$id",
            timelineOrder = id,
        )

        runBlocking {
            db.messageDao().insertAll(
                (1L..700L).map { id ->
                    row(id, if (id == 600L || id == 700L) MessageKind.PRIVMSG else MessageKind.JOIN)
                },
            )
        }
        val visibility = MessageVisibilitySpec(presenceMode = PresenceMode.ALL)
        val policy = MessageVisibilityPolicy(visibility)
        val viewport = MutableStateFlow<ViewportRefreshAnchor?>(null)
        val repository =
            MessageRepositoryImpl(
                db.bufferDao(),
                db.networkIdentityDao(),
                db.messageDao(),
                db.reactionDao(),
                ChatHistoryMediatorFactory { _, _, _ ->
                    object : RemoteMediator<Int, MessageEntity>() {
                        override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

                        override suspend fun load(
                            loadType: LoadType,
                            state: PagingState<Int, MessageEntity>,
                        ) = MediatorResult.Success(endOfPaginationReached = true)
                    }
                },
                db.historyGapDao(),
            )
        val pages = repository.messages(bufferId, visibility, null, viewport).cachedIn(scope)
        lateinit var items: LazyPagingItems<MessageEntity>
        lateinit var listState: LazyListState
        try {
            compose.setContent {
                val collected = pages.collectAsLazyPagingItems(Dispatchers.Main.immediate)
                val timeline = rememberLazyListState()
                SideEffect {
                    items = collected
                    listState = timeline
                }
                ReportMessageViewport(
                    items = collected,
                    listState = timeline,
                    policy = policy,
                    enabled = true,
                    scopeId = bufferId,
                    followingAtBottom = timeline.firstVisibleItemIndex == 0 && timeline.firstVisibleItemScrollOffset == 0,
                    onAnchor = { viewport.value = it },
                )
                MotdTheme(dynamicColor = false) {
                    MessageList(
                        items = collected,
                        listState = timeline,
                        networkId = 1,
                        bufferId = bufferId,
                        readMarkerTime = TimelineAnchor(599_000, 599, 599),
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
                    )
                }
            }

            fun awaitVisible(
                id: Long,
                count: Int,
            ) {
                try {
                    compose.waitUntil(10_000) {
                        compose.waitForIdle()
                        items.itemCount == count && items.loadState.source.refresh is LoadState.NotLoading &&
                            runCatching {
                                compose.onNodeWithTag("chat_message_presence-$id", useUnmergedTree = true).assertIsDisplayed()
                            }.isSuccess
                    }
                } catch (failure: Throwable) {
                    val snapshot = items.itemSnapshotList
                    throw AssertionError(
                        "row=$id count=${items.itemCount} loaded=${snapshot.placeholdersBefore}..${snapshot.size - snapshot.placeholdersAfter} " +
                            "first=${listState.firstVisibleItemIndex} visible=${listState.layoutInfo.visibleItemsInfo.map { it.index to it.key }} " +
                            "anchor=${viewport.value} refresh=${items.loadState.source.refresh}",
                        failure,
                    )
                }
            }

            fun assertFilledViewport() {
                compose.runOnIdle {
                    val layout = listState.layoutInfo
                    val visible =
                        layout.visibleItemsInfo.filter {
                            it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset
                        }
                    assertTrue("No visible timeline rows", visible.isNotEmpty())
                    val placeholders = visible.filter { items.peek(it.index) == null }
                    assertTrue(
                        "Collapsed presence left placeholders in the viewport: ${placeholders.map { it.index }}",
                        placeholders.isEmpty(),
                    )
                    assertTrue(
                        "Timeline has a blank top edge",
                        visible.minOf { it.offset } <= layout.viewportStartOffset + 32,
                    )
                    assertTrue(
                        "Timeline has a blank bottom edge",
                        visible.maxOf { it.offset + it.size } >= layout.viewportEndOffset - 32,
                    )
                }
            }
            awaitVisible(700, 700)
            compose.runOnIdle {
                assertEquals(ViewportRefreshAnchor.Newest, viewport.value)
                assertEquals(700L, items.peek(0)?.id)
            }
            assertFilledViewport()
            // Each Room commit invalidates the positional PagingSource after a viewport full of
            // 1dp collapsed members has sent access hints far beyond slot zero.
            (701L..708L).forEach { id ->
                runBlocking { db.messageDao().insertAll(listOf(row(id, MessageKind.JOIN))) }
                awaitVisible(700, id.toInt())
                val chunk =
                    (id downTo 701L)
                        .takeWhile { candidate -> candidate == id || !isSystemRunChunkBoundary(candidate) }
                        .map { candidate -> row(candidate, MessageKind.JOIN) }
                val summary = if (chunk.size == 1) chunk.first().text else summarizeSystemRun(chunk)
                compose
                    .onAllNodes(
                        hasTestTag("chat_system_pill") and hasAnyDescendant(hasText(summary)),
                        useUnmergedTree = true,
                    ).onFirst()
                    .assertIsDisplayed()
                compose.runOnIdle { assertEquals("Newest page was evicted", id, items.peek(0)?.id) }
            }
            assertFilledViewport()
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(108)
            awaitVisible(600, 708)
            compose.runOnIdle { assertTrue("History viewport returned to newest", listState.firstVisibleItemIndex > 0) }
            compose.runOnIdle { assertTrue("History viewport was classified as newest", viewport.value is ViewportRefreshAnchor.Parked) }
            (709L..712L).forEach { id ->
                runBlocking { db.messageDao().insertAll(listOf(row(id, MessageKind.PART))) }
                awaitVisible(600, id.toInt())
                compose.runOnIdle { assertTrue("New presence stole the history viewport", listState.firstVisibleItemIndex > 0) }
                compose.runOnIdle { assertTrue("History viewport was classified as newest", viewport.value is ViewportRefreshAnchor.Parked) }
            }
            compose.onNodeWithTag("chat_read_marker_divider", useUnmergedTree = true).assertIsDisplayed()
            // One Room commit for a 64-event peer batch must not reveal skeletons or wallpaper
            // while the first unread chat remains parked in the viewport.
            runBlocking { db.messageDao().insertAll((713L..776L).map { row(it, MessageKind.JOIN) }) }
            awaitVisible(600, 776)
            compose.onNodeWithTag("chat_read_marker_divider", useUnmergedTree = true).assertIsDisplayed()
            assertFilledViewport()
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(775)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                items.peek(775)?.id == 1L
            }
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
            awaitVisible(700, 776)
            compose.runOnIdle {
                assertEquals(ViewportRefreshAnchor.Newest, viewport.value)
                assertEquals(776L, items.peek(0)?.id)
            }
            assertFilledViewport()
            val finalChunk =
                (776L downTo 701L)
                    .takeWhile { id -> id == 776L || !isSystemRunChunkBoundary(id) }
                    .map { id -> row(id, if (id in 709L..712L) MessageKind.PART else MessageKind.JOIN) }
            val finalSummary = if (finalChunk.size == 1) finalChunk.first().text else summarizeSystemRun(finalChunk)
            compose
                .onAllNodes(
                    hasTestTag("chat_system_pill") and hasAnyDescendant(hasText(finalSummary)),
                    useUnmergedTree = true,
                ).onFirst()
                .assertIsDisplayed()
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun smartModeStormKeepsCollapsedViewportFilledAcrossRoomInvalidations() {
        val db = inMemoryDb(directCommit = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val bufferId =
            runBlocking {
                val networkId = db.networkDao().insert(network())
                db.bufferDao().insert(buffer(networkId, "#smart"))
            }

        fun mode(id: Long) =
            MessageEntity(
                id = id,
                bufferId = bufferId,
                msgid = "mode-$id",
                serverTime = id * 1_000,
                sender = "peer",
                normalizedActor = "peer",
                kind = MessageKind.MODE,
                text = "peer set mode +q #smart $id",
                dedupKey = "mode-$id",
                timelineOrder = id,
            )
        runBlocking { db.messageDao().insertAll((1L..700L).map(::mode)) }
        val visibility = MessageVisibilitySpec(presenceMode = PresenceMode.SMART)
        val viewport = MutableStateFlow<ViewportRefreshAnchor?>(null)
        val policy = MessageVisibilityPolicy(visibility)
        val repository =
            MessageRepositoryImpl(
                db.bufferDao(),
                db.networkIdentityDao(),
                db.messageDao(),
                db.reactionDao(),
                ChatHistoryMediatorFactory { _, _, _ ->
                    object : RemoteMediator<Int, MessageEntity>() {
                        override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

                        override suspend fun load(
                            loadType: LoadType,
                            state: PagingState<Int, MessageEntity>,
                        ) = MediatorResult.Success(endOfPaginationReached = true)
                    }
                },
                db.historyGapDao(),
            )
        val pages = repository.messages(bufferId, visibility, null, viewport).cachedIn(scope)
        lateinit var items: LazyPagingItems<MessageEntity>
        lateinit var listState: LazyListState
        try {
            compose.setContent {
                val collected = pages.collectAsLazyPagingItems(Dispatchers.Main.immediate)
                val timeline = rememberLazyListState()
                SideEffect {
                    items = collected
                    listState = timeline
                }
                ReportMessageViewport(
                    items = collected,
                    listState = timeline,
                    policy = policy,
                    enabled = true,
                    scopeId = bufferId,
                    followingAtBottom = timeline.firstVisibleItemIndex == 0 && timeline.firstVisibleItemScrollOffset == 0,
                    onAnchor = { viewport.value = it },
                )
                MotdTheme(dynamicColor = false) {
                    MessageList(
                        items = collected,
                        listState = timeline,
                        networkId = 1,
                        bufferId = bufferId,
                        readMarkerTime = null,
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
                    )
                }
            }

            fun assertFilledViewport(newest: Long) {
                compose.waitUntil(10_000) {
                    compose.waitForIdle()
                    items.itemCount == newest.toInt() && items.loadState.source.refresh is LoadState.NotLoading &&
                        items.peek(0)?.id == newest
                }
                compose.runOnIdle {
                    val layout = listState.layoutInfo
                    val visible =
                        layout.visibleItemsInfo.filter {
                            it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset
                        }
                    assertTrue("No visible SMART timeline rows", visible.isNotEmpty())
                    assertTrue("SMART MODE left placeholders in the viewport", visible.none { items.peek(it.index) == null })
                    assertTrue("SMART timeline has a blank top edge", visible.minOf { it.offset } <= layout.viewportStartOffset + 32)
                    assertTrue(
                        "SMART timeline has a blank bottom edge",
                        visible.maxOf { it.offset + it.size } >= layout.viewportEndOffset - 32,
                    )
                }
            }
            assertFilledViewport(700)
            // Every MODE is still visible in SMART; successive commits must refill the collapsed
            // viewport instead of showing full-height placeholder skeletons or bare wallpaper.
            (701L..764L).forEach { id ->
                runBlocking { db.messageDao().insertAll(listOf(mode(id))) }
                assertFilledViewport(id)
            }
            compose.onAllNodesWithTag("chat_system_pill", useUnmergedTree = true).onFirst().assertIsDisplayed()
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun deepSavedEntryKeepsItsExactRowAcrossPresenceInvalidations() {
        val db = inMemoryDb(directCommit = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val (bufferId, room) =
            runBlocking {
                val networkId = db.networkDao().insert(network())
                val room = buffer(networkId, "#deep-entry").copy(joined = true)
                db.bufferDao().insert(room) to room
            }

        fun row(id: Long): MessageEntity {
            val chat = id == 1_200L || id == 3_000L
            return MessageEntity(
                id = id,
                bufferId = bufferId,
                msgid = "entry-$id",
                serverTime = id * 1_000,
                sender = if (chat) "alice" else "peer",
                normalizedActor = if (chat) "alice" else "peer",
                kind =
                    when {
                        chat -> MessageKind.PRIVMSG
                        id % 2 == 0L -> MessageKind.JOIN
                        else -> MessageKind.PART
                    },
                text = if (chat) "Chat $id" else "peer presence $id",
                dedupKey = "entry-$id",
                timelineOrder = id,
            )
        }
        runBlocking { db.messageDao().insertAll((1L..3_000L).map(::row)) }
        val target =
            ChatPositionTarget(
                index = 1_800,
                expectedEventId = 1_200,
                expectedMsgid = "entry-1200",
                fromSavedPosition = true,
            )
        val viewport = MutableStateFlow<ViewportRefreshAnchor?>(ViewportRefreshAnchor.Parked(1_200))
        val repository =
            MessageRepositoryImpl(
                db.bufferDao(),
                db.networkIdentityDao(),
                db.messageDao(),
                db.reactionDao(),
                ChatHistoryMediatorFactory { _, _, _ ->
                    object : RemoteMediator<Int, MessageEntity>() {
                        override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

                        override suspend fun load(
                            loadType: LoadType,
                            state: PagingState<Int, MessageEntity>,
                        ) = MediatorResult.Success(endOfPaginationReached = true)
                    }
                },
                db.historyGapDao(),
            )
        val pages =
            repository
                .messages(
                    bufferId,
                    MessageVisibilitySpec(presenceMode = PresenceMode.ALL),
                    entryAnchorPagingKey(target.index),
                    viewport,
                ).cachedIn(scope)
        lateinit var items: LazyPagingItems<MessageEntity>
        var settled = false
        var reresolves = 0
        var saved: ChatScrollPosition? = null
        try {
            // The ViewModel resolved index 1800 before these commits; first presentation already
            // contains a different row at that index.
            (3_001L..3_004L).forEach { id ->
                runBlocking { db.messageDao().insertAll(listOf(row(id))) }
            }
            compose.setContent {
                val collected = pages.collectAsLazyPagingItems(Dispatchers.Main.immediate)
                SideEffect { items = collected }
                MotdTheme(dynamicColor = false) {
                    ChatContent(
                        state =
                            ChatState(
                                buffer = room.copy(id = bufferId),
                                connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                                conversationPresence = ConversationPresenceState(global = PresenceMode.ALL),
                            ),
                        items = collected,
                        composerEnabled = false,
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
                        showImages = false,
                        showLinkPreviews = false,
                        viewportReadEnabled = false,
                        initialTarget = target,
                        onInitialPositionHandled = { settled = true },
                        onScrollPositionChanged = { saved = it },
                        onReresolveInitial = { reresolves++ },
                        onViewportRefreshAnchor = { viewport.value = it },
                    )
                }
            }
            var parkedAnchor: ViewportRefreshAnchor? = null

            fun assertEntryAt(
                currentIndex: Int,
                itemCount: Int,
            ) {
                try {
                    compose.waitUntil(10_000) {
                        compose.waitForIdle()
                        settled && items.itemCount == itemCount && items.loadState.source.refresh is LoadState.NotLoading &&
                            runCatching {
                                compose.onNodeWithTag("chat_message_entry-1200", useUnmergedTree = true).assertIsDisplayed()
                            }.isSuccess
                    }
                } catch (failure: Throwable) {
                    val snapshot = items.itemSnapshotList
                    throw AssertionError(
                        "settled=$settled reresolves=$reresolves count=${items.itemCount} " +
                            "loaded=${snapshot.placeholdersBefore}..${snapshot.size - snapshot.placeholdersAfter} " +
                            "target=${snapshot.getOrNull(currentIndex)?.id} anchor=${viewport.value} " +
                            "saved=$saved loads=${items.loadState}",
                        failure,
                    )
                }
                compose.runOnIdle {
                    assertEquals("Entry drifted from its canonical row", 1_200L, items.peek(currentIndex)?.id)
                    val anchor = viewport.value
                    assertTrue("Entry jumped to the newest page", anchor is ViewportRefreshAnchor.Parked)
                    if (parkedAnchor == null) parkedAnchor = anchor else assertEquals("Entry viewport moved", parkedAnchor, anchor)
                    assertEquals("Entry re-resolved instead of positioning the loaded ID", 0, reresolves)
                }
            }
            assertEntryAt(1_804, 3_004)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                saved?.index == 1_804 && saved.rowId == 1_200L
            }
            (3_005L..3_010L).forEach { id ->
                runBlocking { db.messageDao().insertAll(listOf(row(id))) }
                assertEntryAt((id - 1_200).toInt(), id.toInt())
            }
            assertEquals(
                (1L..3_010L).map { id -> row(id).let { Triple(it.id, it.kind, it.text) } },
                runBlocking { db.messageDao().historyRowsForMerge(bufferId) }
                    .map { Triple(it.id, it.kind, it.text) },
            )
        } finally {
            scope.cancel()
            db.close()
        }
    }

    private fun assertHistoryDemand(holdAppendResult: Boolean) {
        val db = inMemoryDb(directCommit = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val bufferId =
            runBlocking {
                val networkId = db.networkDao().insert(network())
                db.bufferDao().insert(buffer(networkId, "#history"))
            }

        fun row(ordinal: Long): MessageEntity {
            val presence = ordinal % 25 == 0L
            return MessageEntity(
                id = ordinal,
                bufferId = bufferId,
                msgid = "m$ordinal",
                serverTime = ordinal * 1_000,
                sender = if (presence) "silent" else "alice",
                normalizedActor = if (presence) "silent" else "alice",
                kind = if (presence) MessageKind.JOIN else MessageKind.PRIVMSG,
                text = "History row $ordinal",
                dedupKey = "history-$ordinal",
                timelineOrder = ordinal,
            )
        }

        // Fifty raw rows begin below initialLoadSize=600. SMART presence removes two per page,
        // just as the live timeline's filtered Room counts differ from its 50-row history pages.
        runBlocking { db.messageDao().insertAll((651L..700L).map(::row)) }
        val requestedBoundaries = mutableListOf<Long>()
        var responseReady: CompletableDeferred<Unit>? = null
        var resultReady: CompletableDeferred<Unit>? = null
        var generations = 0
        val mediator =
            object : RemoteMediator<Int, MessageEntity>() {
                override suspend fun initialize(): InitializeAction = InitializeAction.SKIP_INITIAL_REFRESH

                override suspend fun load(
                    loadType: LoadType,
                    state: PagingState<Int, MessageEntity>,
                ): MediatorResult {
                    if (loadType != LoadType.APPEND) {
                        return MediatorResult.Success(endOfPaginationReached = loadType == LoadType.PREPEND)
                    }
                    val oldest = checkNotNull(db.messageDao().oldestTime(bufferId)) / 1_000
                    if (oldest == 1L) return MediatorResult.Success(endOfPaginationReached = true)
                    requestedBoundaries += oldest
                    val response = CompletableDeferred<Unit>()
                    responseReady = response
                    response.await()
                    responseReady = null
                    val result = if (holdAppendResult) CompletableDeferred<Unit>() else null
                    resultReady = result
                    db.messageDao().insertAll((oldest - 50 until oldest).map(::row))
                    // The held ordering lets the replacement source reach its boundary while the
                    // previous APPEND is in flight. The other ordering returns immediately.
                    result?.await()
                    resultReady = null
                    return MediatorResult.Success(endOfPaginationReached = false)
                }
            }
        val pages =
            Pager(
                config = MESSAGE_PAGING_CONFIG,
                remoteMediator = mediator,
                pagingSourceFactory = {
                    generations++
                    db.messageDao().pagingSource(messagePagingQuery(bufferId, MessageVisibilitySpec()))
                },
            ).flow.cachedIn(scope)
        lateinit var items: LazyPagingItems<MessageEntity>
        lateinit var listState: LazyListState

        fun failedState(): String {
            val snapshot = items.itemSnapshotList
            return "requests=$requestedBoundaries generations=$generations count=${items.itemCount} " +
                "loaded=${snapshot.items.firstOrNull()?.msgid}..${snapshot.items.lastOrNull()?.msgid} " +
                "placeholders=${snapshot.placeholdersBefore}/${snapshot.placeholdersAfter} " +
                "visible=${listState.layoutInfo.visibleItemsInfo.map { it.index to it.key }} " +
                "loads=${items.loadState}"
        }

        fun await(
            description: String,
            condition: () -> Boolean,
        ) {
            try {
                compose.waitUntil(5_000) {
                    // Robolectric's paused Android looper owns Main.immediate/Room callbacks;
                    // the test frame clock alone does not drain those queued generation handoffs.
                    compose.waitForIdle()
                    condition()
                }
            } catch (failure: Throwable) {
                throw AssertionError("$description; ${failedState()}", failure)
            }
        }

        try {
            compose.setContent {
                val collected = pages.collectAsLazyPagingItems(Dispatchers.Main.immediate)
                val timeline = rememberLazyListState()
                SideEffect {
                    items = collected
                    listState = timeline
                }
                MotdTheme(dynamicColor = false) {
                    MessageList(
                        items = collected,
                        listState = timeline,
                        networkId = 1,
                        bufferId = bufferId,
                        readMarkerTime = null,
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
                    )
                }
            }
            await("Initial retained messages did not paint") { items.itemCount > 0 }
            for (oldest in 651L downTo 51L step 50) {
                val countBefore = items.itemCount
                compose.onNodeWithTag("chat_timeline").performScrollToIndex(countBefore - 1)
                await("Reaching retained tail m$oldest did not request an older page") {
                    responseReady?.isCompleted == false
                }
                // Leave after this page raises retained rows from 600 raw (576 visible) to
                // 650 raw (624 visible): the newer 600-row refresh now has a local nextKey.
                val leaveTail = !holdAppendResult && oldest == 101L
                val requestsBeforeLeaving = requestedBoundaries.size
                if (leaveTail) {
                    compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
                    compose.onNodeWithTag("chat_message_m699", useUnmergedTree = true).assertIsDisplayed()
                }
                compose.runOnIdle { checkNotNull(responseReady).complete(Unit) }
                await("Persisted APPEND before m$oldest did not reach the Room-backed presenter") {
                    items.itemCount > countBefore
                }
                if (leaveTail) {
                    await("Newer viewport did not settle after its already-requested page completed") {
                        val loads = items.loadState
                        val append = loads.source.append
                        loads.source.refresh is LoadState.NotLoading &&
                            loads.mediator?.append is LoadState.NotLoading &&
                            append is LoadState.NotLoading && !append.endOfPaginationReached
                    }
                    compose.mainClock.advanceTimeBy(1_000)
                    compose.waitForIdle()
                    compose.onNodeWithTag("chat_message_m699", useUnmergedTree = true).assertIsDisplayed()
                    assertEquals(
                        "Leaving the older tail requested additional remote history; ${failedState()}",
                        requestsBeforeLeaving,
                        requestedBoundaries.size,
                    )
                }
                // These are real LazyColumn scrolls. Only production MessageList calls items[index];
                // passive snapshots and node assertions must not repair missing Paging access hints.
                compose.onNodeWithTag("chat_timeline").performScrollToIndex(items.itemCount - 1)
                await("Newly retained older message m${oldest - 50} remained unreachable") {
                    val append = items.loadState.source.append
                    append is LoadState.NotLoading && append.endOfPaginationReached &&
                        runCatching {
                            compose.onNodeWithTag("chat_message_m${oldest - 50}", useUnmergedTree = true).assertIsDisplayed()
                        }.isSuccess
                }
                if (holdAppendResult) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnIdle { checkNotNull(resultReady).complete(Unit) }
                }
            }
            compose.onNodeWithTag("chat_message_m1", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(700, runBlocking { db.messageDao().countForBuffer(bufferId) })
            assertEquals((651L downTo 51L step 50).toList(), requestedBoundaries)
        } finally {
            compose.runOnIdle { scope.cancel() }
            db.close()
        }
    }
}
