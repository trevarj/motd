package io.github.trevarj.motd

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.settingsDataStore
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.repo.ViewportRefreshAnchor
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.CHAT_ENTRY_VEIL_TAG
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatPositionTarget
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ChatViewModel
import io.github.trevarj.motd.ui.chat.ChatViewModelTest
import io.github.trevarj.motd.ui.chat.ConversationPresenceState
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.chat.FoolCollapseChip
import io.github.trevarj.motd.ui.components.FoolVisibilityExample
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class FoolCollapseUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun collapse_affordance_fills_its_timeline_width_and_remains_tappable() {
        var collapsed = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.width(240.dp).testTag("timeline_width")) {
                    FoolCollapseChip("fool", "chat_fool_collapse_msgid-1") { collapsed = true }
                }
            }
        }
        compose.onNodeWithTag("chat_fool_collapse_msgid-1").assertWidthIsEqualTo(240.dp).performTouchInput {
            click(Offset(with(compose.density) { 240.dp.toPx() - 2f }, with(compose.density) { 12.dp.toPx() }))
        }
        assertTrue(collapsed)
    }

    @Test
    fun settingsExampleKeepsExpansionLocalAndShowsAllThreeVisibilityOutcomes() {
        val mode = mutableStateOf(FoolsMode.DIMMED)
        compose.setContent { MotdTheme(dynamicColor = false) { FoolVisibilityExample(mode.value) } }
        compose.onNodeWithText("See you in the channel!", substring = true).assertExists()
        compose.runOnIdle { mode.value = FoolsMode.COLLAPSE }
        compose.onNodeWithText("See you in the channel!", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("fool_example_placeholder").performClick()
        compose.onNodeWithText("See you in the channel!", substring = true).assertExists()
        compose.onNodeWithTag("fool_example_collapse_again").performClick()
        compose.onNodeWithTag("fool_example_placeholder").assertExists()
        compose.runOnIdle { mode.value = FoolsMode.HIDE }
        compose.onNodeWithTag("fool_example_placeholder").assertDoesNotExist()
        compose.onNodeWithText("See you in the channel!", substring = true).assertDoesNotExist()
        compose.onNodeWithText("The example row is hidden;", substring = true).assertExists()
    }

    @Test
    fun collapsedFoolContentHasIntermediateHeightInBothDirections() {
        val fixture = FoolTimelineFixture()
        try {
            compose.setContent { fixture.Content() }
            compose.mainClock.autoAdvance = false
            val host = compose.onNodeWithTag("chat_fool_content_fool-b", useUnmergedTree = true)
            val collapsed = host.fetchSemanticsNode().boundsInRoot.height
            compose.onNodeWithTag("chat_message_fool-b", useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            val opening = host.fetchSemanticsNode().boundsInRoot.height
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val expanded = host.fetchSemanticsNode().boundsInRoot.height
            assertTrue("expanded content must be taller than its placeholder", expanded > collapsed + 4f)
            assertTrue("Show must have a real intermediate height", opening > collapsed + 1f && opening < expanded - 1f)
            compose.onNodeWithTag("chat_fool_collapse_fool-b").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            val closing = host.fetchSemanticsNode().boundsInRoot.height
            assertTrue("Restore must have a real intermediate height", closing > collapsed + 1f && closing < expanded - 1f)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertEquals(collapsed, host.fetchSemanticsNode().boundsInRoot.height, 1f)
        } finally {
            compose.mainClock.autoAdvance = true
            fixture.close()
        }
    }

    @Test fun mixed_timeline_normal_restore_show_all_and_exact_collapsed_exception_are_session_local() {
        val fixture = FoolTimelineFixture()
        try {
            compose.setContent { fixture.Content() }
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertDoesNotExist()
            compose.onNodeWithText("A body").assertIsDisplayed()
            compose.onNodeWithText("B body").assertDoesNotExist()
            compose.onNodeWithTag("chat_message_fool-c", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("a · Show normally").performClick()
            compose.onNodeWithText("a · Restore fool visibility").assertIsDisplayed().performClick()
            compose.onNodeWithText("a · Show normally").assertIsDisplayed()
            compose.onNodeWithTag("chat_message_fool-b", useUnmergedTree = true).performClick()
            compose.onNodeWithText("B body").assertIsDisplayed()
            compose.onNodeWithTag("chat_fool_collapse_fool-b").performClick()
            compose.onNodeWithText("B body").assertDoesNotExist()

            toggleAll()
            listOf("A body", "B body", "C body", "D body").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            compose.onNodeWithTag("chat_fool_collapse_fool-b").performClick()
            compose.onNodeWithText("B body").assertDoesNotExist()
            compose.onNodeWithTag("chat_fool_collapse_fool-a").performClick()
            compose.onNodeWithText("a · Show normally").assertIsDisplayed()
            compose.onNodeWithText("A body").assertIsDisplayed()
            compose.onNodeWithText("C body").assertIsDisplayed()
            assertMutedAttention("a")
            assertMutedAttention("c")

            compose.runOnIdle { fixture.jumpTo("b") }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                fixture.settledJumps == 1
            }
            compose.onNodeWithText("B body").assertIsDisplayed()
            assertEquals(0, fixture.reresolves)
            toggleAll()
            compose.onNodeWithText("A body").assertIsDisplayed()
            compose.onNodeWithText("a · Show normally").assertIsDisplayed()
            compose.onNodeWithText("B body").assertDoesNotExist()
            compose.onNodeWithTag("chat_message_fool-c", useUnmergedTree = true).assertDoesNotExist()
            toggleAll()
            listOf("A body", "B body", "C body", "D body").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            fixture.assertUnchanged()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun show_fools_restores_mixed_defaults_after_content_remount() {
        val fixture = FoolTimelineFixture()
        try {
            compose.setContent { fixture.Content() }
            repeat(2) {
                compose.onNodeWithText("A body").assertIsDisplayed()
                compose.onNodeWithText("a · Show normally").assertIsDisplayed()
                compose.onNodeWithText("B body").assertDoesNotExist()
                compose.onNodeWithTag("chat_message_fool-b", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("chat_message_fool-c", useUnmergedTree = true).assertDoesNotExist()
                toggleAll()
                listOf("A body", "B body", "C body", "D body").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
                compose.runOnIdle { fixture.contentAvailable.value = false }
                compose.onNodeWithTag("chat_overflow").assertDoesNotExist()
                compose.runOnIdle { fixture.contentAvailable.value = true }
                compose.onNodeWithTag("chat_overflow").assertIsDisplayed()
                toggleAll()
                compose.onNodeWithText("A body").assertIsDisplayed()
                compose.onNodeWithText("a · Show normally").assertIsDisplayed()
                compose.onNodeWithText("B body").assertDoesNotExist()
                compose.onNodeWithTag("chat_message_fool-b", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("chat_message_fool-c", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithTag("chat_message_fool-d", useUnmergedTree = true).assertDoesNotExist()
                fixture.assertUnchanged()
            }
        } finally {
            fixture.close()
        }
    }

    @Test fun hidden_absent_from_paging_waits_for_matching_generation_and_expands_only_exact_target() {
        val fixture = FoolTimelineFixture()
        try {
            compose.setContent { fixture.Content() }
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertDoesNotExist()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                fixture.items?.itemCount == 2
            }
            compose.onNodeWithTag("chat_message_fool-c", useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle {
                fixture.holdRevealPresentation.value = true
                fixture.jumpTo("c")
            }
            compose.waitForIdle()
            assertEquals(0, fixture.settledJumps)
            assertEquals(0, fixture.reresolves)
            assertEquals(2, fixture.items?.itemCount)
            compose.onNodeWithText("C body").assertDoesNotExist()
            compose.runOnIdle { fixture.holdRevealPresentation.value = false }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                fixture.settledJumps == 1 && fixture.items?.itemCount == 4
            }
            compose.onNodeWithText("C body").assertIsDisplayed()
            compose.onNodeWithText("D body").assertDoesNotExist()
            compose.onNodeWithTag("chat_message_fool-d", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("B body").assertDoesNotExist()
            assertMutedAttention("c")
            assertEquals(0, fixture.reresolves)
            fixture.assertUnchanged()
        } finally {
            fixture.close()
        }
    }

    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    @Test
    fun pending_normal_entry_restarts_on_visibility_generation_while_first_load_is_held() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DataStoreSettingsRepository(context)
        val db = inMemoryDb(directCommit = true)
        val room =
            runBlocking {
                context.settingsDataStore.edit { it.clear() }
                repo.setFool("bot", true)
                repo.setFoolPreferences("bot", FoolPreferences(FoolsMode.HIDE))
                val networkId = db.networkDao().insert(network())
                val room = buffer(networkId, "#pending-entry").copy(joined = true)
                room.copy(id = db.bufferDao().insert(room))
            }
        val rows =
            listOf("alice", "bot").mapIndexed { index, nick ->
                MessageEntity(
                    id = index + 1L,
                    bufferId = room.id,
                    msgid = "pending-entry-$nick",
                    serverTime = (index + 1L) * 1_000,
                    timelineOrder = index + 1L,
                    sender = nick,
                    normalizedActor = nick,
                    kind = MessageKind.PRIVMSG,
                    text = "Pending entry $nick",
                    dedupKey = "pending-entry-$nick",
                )
            }
        runBlocking { db.messageDao().insertAll(rows) }
        val initialSettings = runBlocking { repo.settings.first() }
        val firstLoadStarted = CompletableDeferred<Unit>()
        val firstLoadRelease = CompletableDeferred<Unit>()
        val firstLoadRetired = CompletableDeferred<Unit>()
        val stored =
            MessageRepositoryImpl(
                db.bufferDao(),
                db.networkIdentityDao(),
                db,
                db.reactionDao(),
                ChatHistoryMediatorFactory { _, _, _ -> error("test controls paging loads") },
                db.historyGapDao(),
            )
        val messages =
            object : MessageRepository by stored {
                override fun messages(
                    bufferId: Long,
                    visibility: MessageVisibilitySpec,
                    initialKey: Int?,
                    viewport: StateFlow<ViewportRefreshAnchor?>,
                ): Flow<PagingData<MessageEntity>> =
                    Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = true), initialKey = initialKey) {
                        val source = db.messageDao().pagingSource(messagePagingQuery(bufferId, visibility))
                        object : PagingSource<Int, MessageEntity>() {
                            override fun getRefreshKey(state: PagingState<Int, MessageEntity>): Int? = source.getRefreshKey(state)

                            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageEntity> {
                                if (!visibility.revealHiddenFools && params is LoadParams.Refresh) {
                                    firstLoadStarted.complete(Unit)
                                    try {
                                        firstLoadRelease.await()
                                    } finally {
                                        firstLoadRetired.complete(Unit)
                                    }
                                }
                                return source.load(params)
                            }
                        }
                    }.flow
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        var items: LazyPagingItems<MessageEntity>? = null
        var settled = 0
        var unresolved = 0
        var repaired = 0
        compose.runOnIdle {
            vm = ChatViewModelTest.externalTranslationFixture(db, room, scope, settings = repo, messages = messages)
            store.put("pending-entry", vm)
        }
        try {
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                vm.messageGeneration.value != null
            }
            compose.setContent {
                val generation by vm.messageGeneration.collectAsState()
                val collected =
                    key(generation) {
                        checkNotNull(generation).pages.collectAsLazyPagingItems(Dispatchers.Main.immediate)
                    }
                val settings by vm.settings.collectAsState(initialSettings)
                val state by vm.state.collectAsState()
                val initialTarget by vm.initialTarget.collectAsState()
                val entryState by vm.entryState.collectAsState()
                val revealed by vm.hiddenFoolsRevealed.collectAsState()
                val expandAllFools by vm.expandAllFools.collectAsState()
                SideEffect { items = collected }
                MotdTheme(dynamicColor = false) {
                    ChatContent(
                        state = state,
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
                        fools = settings.fools,
                        foolsMode = settings.foolsMode,
                        foolPreferences = settings.foolPreferences,
                        hiddenFoolsRevealed = revealed,
                        presentedVisibilitySpec = generation?.spec,
                        expandAllFools = expandAllFools,
                        onExpandAllFoolsChange = vm::setExpandAllFools,
                        viewportReadEnabled = false,
                        initialTarget = initialTarget,
                        entryState = entryState,
                        onInitialPositionHandled = {
                            settled++
                            vm.onInitialPositionHandled()
                        },
                        onInitialPositionUnresolved = {
                            unresolved++
                            vm.onInitialPositionUnresolved()
                        },
                        onReresolveInitial = { repaired++ },
                    )
                }
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                firstLoadStarted.isCompleted && vm.initialTarget.value != null
            }
            val pendingTarget = checkNotNull(vm.initialTarget.value)
            assertNull(pendingTarget.requiredVisibilitySpec)
            assertEquals(rows.first().id, pendingTarget.expectedEventId)
            assertEquals(0, pendingTarget.index)
            assertEquals(EntryPositionState.Pending, vm.entryState.value)
            val retiredItems = items
            toggleAll()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                settled == 1 && items !== retiredItems && items?.itemCount == 2
            }
            assertTrue(firstLoadRetired.isCompleted)
            assertFalse(firstLoadRelease.isCompleted)
            assertEquals(0, unresolved)
            assertEquals(0, repaired)
            assertNull(vm.initialTarget.value)
            assertEquals(EntryPositionState.Settled, vm.entryState.value)
            assertEquals(rows.first().id, items?.peek(1)?.id)
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertDoesNotExist()
            compose.onNodeWithText("Pending entry alice").assertIsDisplayed()
            compose.onNodeWithText("Pending entry bot").assertIsDisplayed()
            assertEquals(initialSettings, runBlocking { repo.settings.first() })
        } finally {
            compose.runOnIdle {
                store.clear()
                scope.cancel()
            }
            compose.waitForIdle()
            db.close()
        }
    }

    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    @Test
    fun settled_reaction_window_follows_revealed_paging_generation_and_viewport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DataStoreSettingsRepository(context)
        val db = inMemoryDb(directCommit = true)
        val room =
            runBlocking {
                context.settingsDataStore.edit { it.clear() }
                repo.setFool("bot", true)
                repo.setFoolPreferences("bot", FoolPreferences(FoolsMode.HIDE))
                val networkId = db.networkDao().insert(network())
                val room = buffer(networkId, "#reactions").copy(joined = true)
                room.copy(id = db.bufferDao().insert(room))
            }
        val rows =
            (1L..60L).map { id ->
                val nick = if (id == 1L) "bot" else "alice"
                MessageEntity(
                    id = id,
                    bufferId = room.id,
                    msgid = "reaction-$id",
                    serverTime = id * 1_000,
                    timelineOrder = id,
                    sender = nick,
                    normalizedActor = nick,
                    kind = MessageKind.PRIVMSG,
                    text = "Reaction body $id\nSecond line",
                    dedupKey = "reaction-$id",
                )
            }
        runBlocking {
            db.messageDao().insertAll(rows)
            db.reactionDao().upsert(
                ReactionEntity(
                    bufferId = room.id,
                    targetMsgid = "reaction-1",
                    actorKey = "nick:alice",
                    sender = "alice",
                    emoji = "\u2B50",
                    serverTime = 61_000,
                    targetEventId = 1,
                ),
            )
        }
        val initialSettings = runBlocking { repo.settings.first() }
        val stored =
            MessageRepositoryImpl(
                db.bufferDao(),
                db.networkIdentityDao(),
                db,
                db.reactionDao(),
                ChatHistoryMediatorFactory { _, _, _ -> error("local paging only") },
                db.historyGapDao(),
            )
        val messages =
            object : MessageRepository by stored {
                override fun messages(
                    bufferId: Long,
                    visibility: MessageVisibilitySpec,
                    initialKey: Int?,
                    viewport: StateFlow<ViewportRefreshAnchor?>,
                ): Flow<PagingData<MessageEntity>> =
                    Pager(PagingConfig(pageSize = 80, initialLoadSize = 80, enablePlaceholders = true)) {
                        db.messageDao().pagingSource(messagePagingQuery(bufferId, visibility))
                    }.flow
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        var items: LazyPagingItems<MessageEntity>? = null
        var visibleMsgids = emptyList<String>()
        compose.runOnIdle {
            vm =
                ChatViewModelTest.externalTranslationFixture(
                    db,
                    room,
                    scope,
                    savedStateHandle = SavedStateHandle(mapOf("bufferId" to room.id, "entry_position_settled" to true)),
                    settings = repo,
                    messages = messages,
                )
            store.put("reaction-generation", vm)
        }
        try {
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                vm.messageGeneration.value != null
            }
            compose.setContent {
                val generation by vm.messageGeneration.collectAsState()
                val collected = key(generation) { checkNotNull(generation).pages.collectAsLazyPagingItems(Dispatchers.Main.immediate) }
                val settings by vm.settings.collectAsState(initialSettings)
                val state by vm.state.collectAsState()
                val revealed by vm.hiddenFoolsRevealed.collectAsState()
                val expandAllFools by vm.expandAllFools.collectAsState()
                val entryState by vm.entryState.collectAsState()
                val chips by vm.reactionChips.collectAsState()
                SideEffect { items = collected }
                MotdTheme(dynamicColor = false) {
                    ChatContent(
                        state = state,
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
                        fools = settings.fools,
                        foolsMode = settings.foolsMode,
                        foolPreferences = settings.foolPreferences,
                        hiddenFoolsRevealed = revealed,
                        presentedVisibilitySpec = generation?.spec,
                        expandAllFools = expandAllFools,
                        onExpandAllFoolsChange = vm::setExpandAllFools,
                        viewportReadEnabled = false,
                        entryState = entryState,
                        reactionChips = { chips[it].orEmpty() },
                        onVisibleMsgidsChanged = {
                            visibleMsgids = it
                            vm.setVisibleMsgids(it)
                        },
                    )
                }
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                items?.itemCount == 59 && visibleMsgids.isNotEmpty()
            }
            compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertDoesNotExist()
            compose.onNodeWithTag("chat_message_reaction-1", useUnmergedTree = true).assertDoesNotExist()
            assertFalse("reaction-1" in visibleMsgids)
            toggleAll()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                items?.itemCount == 60
            }
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(59)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                "reaction-1" in visibleMsgids && vm.reactionChips.value["reaction-1"]
                    ?.singleOrNull()
                    ?.count == 1
            }
            compose.onNodeWithText("Reaction body 1\nSecond line", useUnmergedTree = true).assertIsDisplayed()
            compose
                .onNode(hasText("\u2B50") and hasAnyAncestor(hasTestTag("chat_message_reaction-1")), useUnmergedTree = true)
                .assertIsDisplayed()
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                "reaction-1" !in visibleMsgids && "reaction-1" !in vm.reactionChips.value
            }
            compose.onNodeWithTag("chat_message_reaction-1", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(initialSettings, runBlocking { repo.settings.first() })
        } finally {
            compose.runOnIdle {
                store.clear()
                scope.cancel()
            }
            compose.waitForIdle()
            db.close()
        }
    }

    private fun toggleAll() {
        compose.onNodeWithTag("chat_overflow").performClick()
        compose.onNodeWithTag("chat_toggle_fools_visibility").performScrollTo().performClick()
    }

    private fun assertMutedAttention(nick: String) {
        val semantics = compose.onNodeWithTag("chat_message_fool-$nick", useUnmergedTree = true).fetchSemanticsNode().config
        assertNull(semantics.getOrNull(SemanticsProperties.StateDescription))
    }
}

/** Real Room paging and persisted preferences; also used by the temporary rendered smoke. */
internal class FoolTimelineFixture {
    val repo = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
    private val db = inMemoryDb(directCommit = true)
    private val room =
        runBlocking {
            ApplicationProvider.getApplicationContext<Context>().settingsDataStore.edit { it.clear() }
            val networkId = db.networkDao().insert(network())
            val room = buffer(networkId, "#fools").copy(joined = true)
            room.copy(id = db.bufferDao().insert(room))
        }
    private val rows =
        listOf("a", "b", "c", "d").mapIndexed { index, nick ->
            MessageEntity(
                id = 4L - index,
                bufferId = room.id,
                msgid = "fool-$nick",
                serverTime = 1_700_000_000_000L + (4 - index) * 1_000,
                timelineOrder = 4L - index,
                sender = nick,
                normalizedActor = nick,
                kind = MessageKind.PRIVMSG,
                text = "${nick.uppercase()} body",
                hasMention = true,
                dedupKey = "fool-$nick",
            )
        }
    val initialSettings: Settings =
        runBlocking {
            repo.setFoolsMode(FoolsMode.DIMMED)
            repo.setPresenceMode(PresenceMode.ALL)
            for (nick in listOf("a", "b", "c", "d")) repo.setFool(nick, true)
            repo.setFoolPreferences("b", FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true))
            repo.setFoolPreferences("c", FoolPreferences(FoolsMode.HIDE, notifyMentions = true))
            repo.setFoolPreferences("d", FoolPreferences(FoolsMode.HIDE))
            db.messageDao().insertAll(rows)
            repo.settings.first()
        }
    private val revealed = mutableStateOf(false)
    private val expandAllFools = mutableStateOf(false)
    val holdRevealPresentation = mutableStateOf(false)
    val contentAvailable = mutableStateOf(true)
    private val target = mutableStateOf<ChatPositionTarget?>(null)
    var items: LazyPagingItems<MessageEntity>? = null
    var settledJumps = 0
    var reresolves = 0
    private var jumpToken = 0L

    fun jumpTo(nick: String) {
        val row = rows.single { it.sender == nick }
        var spec = MessageVisibilitySpec.from(runBlocking { repo.settings.first() }).copy(revealHiddenFools = revealed.value)
        if (MessageVisibilityPolicy(spec).foolPreferences(row)?.visibility == FoolsMode.HIDE) {
            revealed.value = true
            spec = spec.copy(revealHiddenFools = true)
        }
        val index = runBlocking { MessageVisibilityReader(db).countTimelineNewer(room.id, row.serverTime, row.id, spec) }
        target.value = ChatPositionTarget(index, expectedEventId = row.id, expectedMsgid = row.msgid, highlightEventId = row.id, requestToken = ++jumpToken, requiredVisibilitySpec = spec)
    }

    @Composable
    fun Content() {
        val settings by repo.settings.collectAsState(initialSettings)
        val desired = MessageVisibilitySpec.from(settings).copy(revealHiddenFools = revealed.value)
        val presented = if (holdRevealPresentation.value && revealed.value) desired.copy(revealHiddenFools = false) else desired
        val pages =
            remember(presented) {
                Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = true)) {
                    db.messageDao().pagingSource(messagePagingQuery(room.id, presented))
                }.flow
            }
        val collected = key(presented) { pages.collectAsLazyPagingItems(Dispatchers.Main.immediate) }
        SideEffect { items = collected }
        // Mirrors ChatScreen's missing-presenter early return without disposing the visit owner.
        if (!contentAvailable.value) return
        MotdTheme(dynamicColor = false) {
            ChatContent(
                state = ChatState(buffer = room, connState = IrcClientState.Ready("me", emptySet(), emptyMap()), conversationPresence = ConversationPresenceState(global = PresenceMode.ALL)),
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
                fools = settings.fools,
                foolsMode = settings.foolsMode,
                foolPreferences = settings.foolPreferences,
                hiddenFoolsRevealed = revealed.value,
                presentedVisibilitySpec = presented,
                expandAllFools = expandAllFools.value,
                onExpandAllFoolsChange = {
                    expandAllFools.value = it
                    revealed.value = it
                },
                viewportReadEnabled = false,
                entryState = EntryPositionState.Settled,
                jumpTarget = target.value,
                onJumpHandled = {
                    settledJumps++
                    target.value = null
                },
                onReresolveJump = { reresolves++ },
            )
        }
    }

    fun assertUnchanged() = assertEquals(initialSettings, runBlocking { repo.settings.first() })

    fun close() {
        db.close()
    }
}
