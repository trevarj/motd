package io.github.trevarj.motd.ui.feed

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Presentation contract of the merged stream, driven off a static [PagingData] rather than the
 * Hilt-owning screen. In `testDebug` because [createComposeRule] needs the debug-only
 * `ComponentActivity` — see `EmptyStateLayoutTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobalFeedScreenTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    private var opened: Triple<Long, Long, Long>? = null

    private fun row(
        id: Long,
        bufferId: Long = 7L,
        buffer: String = "#kotlin",
        network: String = "Libera",
        sender: String = "nick",
        text: String = "hello there",
        kind: MessageKind = MessageKind.PRIVMSG,
        serverTime: Long = 1_700_000_000_000L,
        isSelf: Boolean = false,
        failed: Boolean = false,
        ircFormattedText: String? = null,
    ) = SearchHit(
        message =
            MessageEntity(
                id = id,
                bufferId = bufferId,
                serverTime = serverTime,
                sender = sender,
                kind = kind,
                text = text,
                isSelf = isSelf,
                failed = failed,
                ircFormattedText = ircFormattedText,
                dedupKey = "dedup-$id",
            ),
        bufferDisplayName = buffer,
        networkName = network,
        bufferType = BufferType.CHANNEL,
        networkId = 1L,
    )

    /** Source states for a stream that has finished refreshing, however that refresh ended. */
    private fun settled(refresh: LoadState) =
        LoadStates(
            refresh = refresh,
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )

    /** The stream is built by the caller, so recomposition cannot restart the collection. */
    private fun setContent(
        stream: Flow<PagingData<SearchHit>>,
        showNetwork: () -> Boolean = { false },
    ) {
        compose.setContent {
            // Motion off: the caption waits on a Lottie clock a stub composition never advances.
            CompositionLocalProvider(LocalLottieMotionEnabled provides false) {
                MotdTheme(dynamicColor = false) {
                    GlobalFeedContent(
                        rows = stream.collectAsLazyPagingItems(context = Dispatchers.Unconfined),
                        showNetwork = showNetwork(),
                        onOpenMessage = { bufferId, eventId, serverTime ->
                            opened = Triple(bufferId, eventId, serverTime)
                        },
                    )
                }
            }
        }
    }

    /** The first paging emission lands after composition, so assertions wait on the branch. */
    private fun awaitText(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitTag(tag: String) =
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun aLineShowsItsConversationTagAndFormattedBodyAndReportsTheTappedRow() {
        setContent(
            flowOf(
                PagingData.from(listOf(row(id = 11L, text = "hello there"))),
            ),
        )

        awaitTag("feed_row_11")
        compose.onNodeWithTag("feed_list").assertIsDisplayed()
        compose.onNodeWithText("#kotlin").assertIsDisplayed()
        compose.onNodeWithText("hello there").assertIsDisplayed()
        compose.onNodeWithText("nick").assertIsDisplayed()

        compose.onNodeWithText("hello there").performClick()

        compose.runOnIdle {
            // Canonical row id for identity, serverTime only as the scroll anchor.
            assertEquals(Triple(7L, 11L, 1_700_000_000_000L), opened)
        }
    }

    @Test
    fun aRunFromOneSenderShowsItsNickOnceAndTagsOnlyTheConversationChange() {
        // Newest first: two lines from one nick, then another conversation.
        setContent(
            flowOf(
                PagingData.from(
                    listOf(
                        row(id = 62L, serverTime = 1_700_000_060_000L),
                        row(id = 61L, serverTime = 1_700_000_000_000L),
                        row(id = 60L, bufferId = 9L, buffer = "#nix", sender = "ana", serverTime = 1_699_999_900_000L),
                    ),
                ),
            ),
        )

        awaitTag("feed_row_60")
        // One group: the nick heads the opening line only; the tag marks the buffer change.
        listOf(62L, 61L, 60L).forEach { compose.onNodeWithTag("feed_row_$it").assertIsDisplayed() }
        compose.onNodeWithText("nick").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("nick").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("#kotlin").fetchSemanticsNodes().size)
        compose.onNodeWithText("ana").assertIsDisplayed()
    }

    @Test
    fun theNetworkLineAppearsOnlyWhileMoreThanOneNetworkExists() {
        var showNetwork by mutableStateOf(false)
        setContent(flowOf(PagingData.from(listOf(row(id = 21L)))), showNetwork = { showNetwork })

        awaitTag("feed_row_21")
        compose.onNodeWithText("#kotlin · Libera").assertDoesNotExist()

        showNetwork = true
        compose.waitForIdle()

        compose.onNodeWithText("#kotlin · Libera").assertIsDisplayed()
    }

    @Test
    fun aSettledEmptyStreamShowsTheEmptyState() {
        // Explicit NotLoading: a plain from(emptyList()) leaves the refresh reading as loading.
        setContent(flowOf(PagingData.from(emptyList(), settled(LoadState.NotLoading(endOfPaginationReached = true)))))

        awaitTag("empty_state_ghost_rows")
        compose.onNodeWithTag("feed_list").assertDoesNotExist()
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun retryingAFailedRefreshAsksThePagerForTheStreamAgain() {
        val loads = AtomicInteger()
        val pager =
            Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
                FailFirstPagingSource(loads, listOf(row(id = 31L)))
            }
        setContent(pager.flow)

        awaitText("Retry")
        compose.onNodeWithTag("feed_list").assertDoesNotExist()
        compose.onNodeWithText("Couldn't load the global feed").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()

        // Proves the tap reached the pager: the second load is what clears the error state.
        awaitTag("feed_row_31")
        compose.onNodeWithText("Couldn't load the global feed").assertDoesNotExist()
        assertEquals(2, loads.get())
    }

    /**
     * The contentType pool already splits SELF_FAILED out, so the row has to render it: a send that
     * failed must look failed here, not like an ordinary delivered line.
     */
    @Test
    fun aFailedSelfLineShowsTheFailureAffordance() {
        setContent(
            flowOf(
                PagingData.from(listOf(row(id = 51L, sender = "me", isSelf = true, failed = true))),
            ),
        )

        awaitTag("feed_row_51")
        compose.onNodeWithContentDescription("Failed").assertIsDisplayed()
    }

    /** Same rule as the chat timeline: the stored IRC-formatted body wins over the raw text. */
    @Test
    fun aLineRendersItsIrcFormattedBodyRatherThanTheRawText() {
        setContent(
            flowOf(
                PagingData.from(
                    listOf(row(id = 52L, text = "\u0002bold\u0002 line", ircFormattedText = "bold line")),
                ),
            ),
        )

        awaitTag("feed_row_52")
        compose.onNodeWithText("bold line").assertIsDisplayed()
    }

    @Test
    fun aFailedRefreshKeepsAlreadyLoadedLinesOnScreen() {
        setContent(
            flowOf(
                PagingData.from(
                    listOf(row(id = 41L)),
                    settled(LoadState.Error(IllegalStateException("boom"))),
                ),
            ),
        )

        awaitTag("feed_row_41")
        compose.onNodeWithText("Couldn't load the global feed").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}

/** Fails its first load and serves [page] afterwards, so a retry shows up as a state change. */
private class FailFirstPagingSource(
    private val loads: AtomicInteger,
    private val page: List<SearchHit>,
) : PagingSource<Int, SearchHit>() {
    override fun getRefreshKey(state: PagingState<Int, SearchHit>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchHit> =
        if (loads.incrementAndGet() == 1) {
            LoadResult.Error(IllegalStateException("boom"))
        } else {
            LoadResult.Page(data = page, prevKey = null, nextKey = null)
        }
}
