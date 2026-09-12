package io.github.trevarj.motd

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.repo.MESSAGE_PAGING_CONFIG
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.ui.chat.MessageList
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

        // Fifty raw rows begin below initialLoadSize=150. SMART presence removes two per page,
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
                // Leave only after 300 retained raw rows (288 visible): a newer 150-row refresh
                // then has a local nextKey, unlike the legitimate initial undersized auto-APPENDs.
                val leaveTail = !holdAppendResult && oldest == 401L
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
