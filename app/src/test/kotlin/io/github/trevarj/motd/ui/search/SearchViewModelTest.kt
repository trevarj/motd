package io.github.trevarj.motd.ui.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.LocalSearchResult
import io.github.trevarj.motd.data.repo.SearchCoverage
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcTimeoutException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.ext.SOJU_SEARCH_MAX_LIMIT
import io.github.trevarj.motd.irc.ext.SearchResultKind
import io.github.trevarj.motd.irc.ext.SearchResultMessage
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import io.github.trevarj.motd.irc.ext.SearchRequest as IrcSearchRequest

/**
 * Query pipeline: every logical query/scope key immediately clears stale rows and publishes
 * loading, while only the repository call is debounced. The component test covers the local IME
 * value's one-frame coherence guard; these tests exercise keyed cancellation at the ViewModel seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    /**
     * Await the first state matching [predicate]. Blank keys now emit a coverage frame of their
     * own, so tests settle on the key under test instead of counting emissions.
     */
    private suspend fun ReceiveTurbine<SearchUiState>.awaitStateWhere(
        predicate: (SearchUiState) -> Boolean,
    ): SearchUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private fun viewModel(
        repo: SearchRepository,
        buffers: BufferRepository = FakeBufferRepository(),
        connections: ConnectionManager = FakeConnectionManager(),
    ) = SearchViewModel(repo, buffers, connections)

    private fun hit(
        bufferId: Long,
        text: String,
        sender: String = "alice",
    ) = SearchHit(
        message =
            MessageEntity(
                id = bufferId,
                bufferId = bufferId,
                serverTime = 1_000L,
                sender = sender,
                kind = MessageKind.PRIVMSG,
                text = text,
                dedupKey = "k$bufferId",
            ),
        bufferDisplayName = "#kotlin",
        networkName = "Libera",
        bufferType = BufferType.CHANNEL,
        networkId = 1,
    )

    /** Counts search() invocations so we can assert the debounce collapses rapid keystrokes. */
    private class FakeSearchRepository(
        private val result: List<SearchHit>,
        val calls: AtomicInteger = AtomicInteger(0),
        private val truncated: Boolean = false,
        private val coverage: SearchCoverage = SearchCoverage.DeviceOnly,
    ) : SearchRepository {
        override fun search(
            query: String,
            bufferId: Long?,
        ): Flow<LocalSearchResult> {
            calls.incrementAndGet()
            return flowOf(LocalSearchResult(result, truncated))
        }

        override fun coverage(bufferId: Long?): Flow<SearchCoverage> = flowOf(coverage)
    }

    private data class SearchRequest(
        val query: String,
        val bufferId: Long?,
    )

    /** A keyed, replaying source lets tests deliver results after its collector was cancelled. */
    private class ControlledSearchRepository(
        private val coverageByScope: Map<Long?, SearchCoverage> = emptyMap(),
    ) : SearchRepository {
        private val flows = mutableMapOf<SearchRequest, MutableSharedFlow<LocalSearchResult>>()
        val calls = mutableListOf<SearchRequest>()

        override fun search(
            query: String,
            bufferId: Long?,
        ): Flow<LocalSearchResult> {
            val request = SearchRequest(query, bufferId)
            calls += request
            return flowFor(request)
        }

        override fun coverage(bufferId: Long?): Flow<SearchCoverage> = flowOf(coverageByScope[bufferId] ?: SearchCoverage.DeviceOnly)

        fun emit(
            query: String,
            bufferId: Long?,
            hits: List<SearchHit>,
            truncated: Boolean = false,
        ) {
            check(flowFor(SearchRequest(query, bufferId)).tryEmit(LocalSearchResult(hits, truncated)))
        }

        private fun flowFor(request: SearchRequest) =
            flows.getOrPut(request) {
                MutableSharedFlow(replay = 1)
            }
    }

    private fun buffer(
        id: Long = BUFFER_ID,
        type: BufferType = BufferType.CHANNEL,
    ) = BufferEntity(
        id = id,
        networkId = NETWORK_ID,
        name = "#kotlin",
        displayName = "#kotlin",
        type = type,
    )

    private fun chatListRow(
        bufferId: Long,
        displayName: String,
        type: BufferType = BufferType.CHANNEL,
        archived: Boolean = false,
        networkName: String = "Libera",
    ) = ChatListRow(
        bufferId = bufferId,
        networkId = NETWORK_ID,
        networkName = networkName,
        displayName = displayName,
        type = type,
        pinned = false,
        muted = false,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = null,
        unreadCount = 0,
        mentionCount = 0,
        archived = archived,
    )

    private class FakeBufferRepository(
        initial: BufferEntity? = null,
        private val chatList: List<ChatListRow> = emptyList(),
    ) : BufferRepository {
        val buffers = MutableStateFlow(initial)

        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(chatList)

        override fun observeBuffer(id: Long): Flow<BufferEntity?> = buffers

        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())

        override suspend fun setPinned(
            id: Long,
            pinned: Boolean,
        ) = Unit

        override suspend fun setMuted(
            id: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = null

        override suspend fun setLayoutDensityOverride(
            id: Long,
            layout: LayoutDensity?,
        ): Boolean = true

        override suspend fun setPresenceModeOverride(
            id: Long,
            mode: PresenceMode?,
        ): Boolean = true

        override suspend fun deleteBuffer(id: Long) = Unit
    }

    /** Interface defaults keep this to the abstract surface plus the two search seams. */
    private class FakeConnectionManager(
        available: Boolean = false,
        private val result: List<SearchResultMessage>? = emptyList(),
        private val failure: Throwable? = null,
    ) : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        val searchAvailable = MutableStateFlow(available)
        val requests = mutableListOf<IrcSearchRequest>()

        override fun serverSearchAvailable(networkId: Long): Boolean = searchAvailable.value

        override suspend fun searchMessages(
            networkId: Long,
            request: IrcSearchRequest,
        ): List<SearchResultMessage>? {
            requests += request
            failure?.let { throw it }
            return result
        }

        private var tick = 0L

        /** Any distinct connection-state emission re-triggers the availability recomputation. */
        fun publishAvailability(value: Boolean) {
            searchAvailable.value = value
            connectionStates.value = mapOf(++tick to IrcClientState.Connecting)
        }

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long = 0

        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
    }

    private fun serverHit(
        text: String,
        serverTime: Long?,
        msgid: String?,
        sender: String = "alice",
    ) = SearchResultMessage(
        target = "#kotlin",
        sender = sender,
        text = text,
        kind = SearchResultKind.PRIVMSG,
        serverTime = serverTime,
        msgid = msgid,
    )

    private fun serverViewModel(
        connections: FakeConnectionManager,
        buffers: FakeBufferRepository = FakeBufferRepository(buffer()),
    ): SearchViewModel = viewModel(FakeSearchRepository(emptyList()), buffers, connections).also { it.init(BUFFER_ID) }

    /**
     * Settles on live availability, then enters SERVER scope with [query] typed.
     *
     * The ViewModel's own coroutines run outside the test scheduler (viewModelScope carries no
     * dispatcher in a plain JVM test), so every step waits on an observed state rather than on
     * `runCurrent()`.
     */
    private suspend fun ReceiveTurbine<SearchUiState>.enterServerScope(
        vm: SearchViewModel,
        query: String = "coroutine",
    ) {
        awaitStateWhere { it.serverSearchAvailable }
        vm.onScopeChange(SearchScope.SERVER)
        vm.onQueryChange(query)
        awaitStateWhere { it.scope == SearchScope.SERVER && it.rawQuery == query }
    }

    @Test
    fun serverChipRequiresAnAvailableClientAndBufferScope() =
        runTest {
            val connections = FakeConnectionManager(available = true)
            val buffers = FakeBufferRepository(buffer())

            // No buffer scope at all: there is no conversation for the server to search.
            val global = viewModel(FakeSearchRepository(emptyList()), buffers, connections)
            global.init(null)
            global.state.test {
                val settled = awaitStateWhere { it.coverage != null }
                assertTrue(!settled.hasBufferScope)
                assertTrue("no buffer scope means no server chip", !settled.serverSearchAvailable)
                cancelAndIgnoreRemainingEvents()
            }

            val scoped = serverViewModel(connections, buffers)
            scoped.state.test {
                assertTrue(awaitStateWhere { it.serverSearchAvailable }.hasBufferScope)

                // The per-network SERVER buffer is not a conversation either.
                buffers.buffers.value = buffer(type = BufferType.SERVER)
                assertTrue(!awaitStateWhere { !it.serverSearchAvailable }.serverSearchAvailable)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun serverSubmitMapsSortsAndFlagsTruncation() =
        runTest {
            val ascending =
                (1..100).map { i ->
                    serverHit("hit $i", serverTime = 1_000L + i, msgid = "m$i")
                }
            val connections = FakeConnectionManager(available = true, result = ascending)
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                val results =
                    awaitStateWhere { it.server is ServerSearchState.Results }
                        .server as ServerSearchState.Results

                assertEquals(100, results.hits.size)
                assertEquals("hit 100", results.hits.first().text)
                assertEquals("hit 1", results.hits.last().text)
                assertTrue("soju's 100-result cap must be disclosed", results.truncated)
                assertEquals(BUFFER_ID, results.hits.first().bufferId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun serverSubmitSendsParsedFromAndTextAttributes() =
        runTest {
            val connections = FakeConnectionManager(available = true)
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm, query = "coroutine builder from:alice")
                vm.onServerSearchSubmit()
                awaitStateWhere { it.server is ServerSearchState.Results }
                cancelAndIgnoreRemainingEvents()
            }

            val request = connections.requests.single()
            assertEquals("#kotlin", request.target)
            assertEquals("coroutine builder", request.text)
            assertEquals("alice", request.from)
            assertEquals(SOJU_SEARCH_MAX_LIMIT, request.limit)
        }

    @Test
    fun serverFailMapsToRejected() =
        runTest {
            val connections =
                FakeConnectionManager(
                    available = true,
                    failure = IrcCommandException("SEARCH", "INVALID_PARAMS", "bad query"),
                )
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                val failed =
                    awaitStateWhere { it.server is ServerSearchState.Failed }
                        .server as ServerSearchState.Failed
                assertEquals(ServerSearchError.REJECTED, failed.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun serverTimeoutMapsToUnavailable() =
        runTest {
            val connections = FakeConnectionManager(available = true, failure = IrcTimeoutException("motd-1"))
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                val failed =
                    awaitStateWhere { it.server is ServerSearchState.Failed }
                        .server as ServerSearchState.Failed
                assertEquals(ServerSearchError.UNAVAILABLE, failed.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun nullClientMapsToUnavailable() =
        runTest {
            // Availability was true when the chip rendered; the client vanished before the request.
            val connections = FakeConnectionManager(available = true, result = null)
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                val failed =
                    awaitStateWhere { it.server is ServerSearchState.Failed }
                        .server as ServerSearchState.Failed
                assertEquals(ServerSearchError.UNAVAILABLE, failed.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun editingTheQueryResetsServerResults() =
        runTest {
            val connections =
                FakeConnectionManager(
                    available = true,
                    result = listOf(serverHit("stale", serverTime = 1_000, msgid = "m1")),
                )
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                awaitStateWhere { it.server is ServerSearchState.Results }

                vm.onQueryChange("coroutines")
                // Results describe the previous query; keeping them would misattribute them.
                awaitStateWhere { it.server is ServerSearchState.Idle }
                assertEquals("typing must not fire a wire request", 1, connections.requests.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun leavingServerScopeResetsServerResults() =
        runTest {
            val connections =
                FakeConnectionManager(
                    available = true,
                    result = listOf(serverHit("stale", serverTime = 1_000, msgid = "m1")),
                )
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                awaitStateWhere { it.server is ServerSearchState.Results }

                vm.onScopeChange(SearchScope.CURRENT)
                val local = awaitStateWhere { it.scope == SearchScope.CURRENT }
                assertEquals(ServerSearchState.Idle, local.server)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun availabilityLossWhileInServerScopeFallsBackToCurrent() =
        runTest {
            val connections =
                FakeConnectionManager(
                    available = true,
                    result = listOf(serverHit("stale", serverTime = 1_000, msgid = "m1")),
                )
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                awaitStateWhere { it.server is ServerSearchState.Results }

                connections.publishAvailability(false)
                val fallen = awaitStateWhere { it.scope == SearchScope.CURRENT && !it.serverSearchAvailable }
                assertEquals(ServerSearchState.Idle, fallen.server)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun hitsWithoutTimeOrMsgidAreDropped() =
        runTest {
            val connections =
                FakeConnectionManager(
                    available = true,
                    result =
                        listOf(
                            serverHit("jumpable", serverTime = 2_000, msgid = "m1"),
                            // Nothing to resolve a jump target from, so it is not a usable result.
                            serverHit("unjumpable", serverTime = null, msgid = null),
                        ),
                )
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                val results =
                    awaitStateWhere { it.server is ServerSearchState.Results }
                        .server as ServerSearchState.Results
                assertEquals(listOf("jumpable"), results.hits.map { it.text })
                assertTrue("a short page is not truncated", !results.truncated)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun msgidOnlyHitsAreKeptWithZeroTime() =
        runTest {
            val connections =
                FakeConnectionManager(
                    available = true,
                    result =
                        listOf(
                            serverHit("no time tag", serverTime = null, msgid = "m-old"),
                            serverHit("timed", serverTime = 5_000, msgid = "m-new"),
                        ),
                )
            val vm = serverViewModel(connections)

            vm.state.test {
                enterServerScope(vm)
                vm.onServerSearchSubmit()
                val results =
                    awaitStateWhere { it.server is ServerSearchState.Results }
                        .server as ServerSearchState.Results
                // Newest-first ordering sinks the untimed hit to the bottom; it still jumps by msgid.
                assertEquals(listOf("timed", "no time tag"), results.hits.map { it.text })
                assertEquals(0L, results.hits.last().serverTime)
                assertEquals("m-old", results.hits.last().msgid)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        const val BUFFER_ID = 7L
        const val NETWORK_ID = 3L
    }

    @Test
    fun blank_query_emits_empty_results_without_hitting_the_repo() =
        runTest {
            val repo = FakeSearchRepository(emptyList())
            val vm = viewModel(repo)

            vm.state.test {
                awaitItem()
                vm.onQueryChange("   ")
                runCurrent()
                // Settle on the key under test: a blank key emits a coverage frame of its own, and a
                // StateFlow conflates, so neither the pristine initial value nor the frame that
                // follows it is guaranteed to be observed as its own emission.
                val blank = awaitStateWhere { it.rawQuery == "   " }
                assertTrue("blank query yields no result groups", blank.groups.isEmpty())
                assertEquals("blank query must not query the DB", 0, repo.calls.get())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun rapid_typing_is_debounced_into_a_single_db_query() =
        runTest {
            val repo = FakeSearchRepository(listOf(hit(1, "coroutine builder")))
            val vm = viewModel(repo)

            vm.state.test {
                awaitItem() // initial

                // Simulate keystrokes faster than the debounce window. Each key is settled on rather
                // than counted: a StateFlow conflates, so an intermediate keystroke's frame is not
                // guaranteed to be observed on its own. What this test protects is the debounce —
                // that no repository call happens until typing pauses — not the emission count.
                vm.onQueryChange("c")
                runCurrent()
                awaitStateWhere { it.rawQuery == "c" }
                advanceTimeBy(50)
                vm.onQueryChange("co")
                runCurrent()
                awaitStateWhere { it.rawQuery == "co" }
                advanceTimeBy(50)
                vm.onQueryChange("cor")
                runCurrent()
                awaitStateWhere { it.rawQuery == "cor" }
                advanceTimeBy(50)
                vm.onQueryChange("coroutine")
                runCurrent()
                val loading = awaitStateWhere { it.rawQuery == "coroutine" }
                assertTrue(loading.searching)
                // Not yet past the debounce window: no query should have fired.
                assertEquals("no DB hit before the typing pause", 0, repo.calls.get())

                // Past the debounce window: exactly one query fires and results arrive.
                advanceTimeBy(300)
                val results = awaitStateWhere { it.rawQuery == "coroutine" && it.groups.isNotEmpty() }
                assertEquals("rapid typing collapses to one DB query", 1, repo.calls.get())
                assertEquals("coroutine", results.rawQuery)
                assertEquals(1, results.groups.size)
                assertEquals(1L, results.groups.first().bufferId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun clear_resets_selection_and_composition() {
        val editing =
            TextFieldValue(
                text = "coroutine",
                selection = TextRange(2, 7),
                composition = TextRange(0, 9),
            )

        val cleared = clearedSearchText()

        assertEquals("", cleared.text)
        assertEquals(TextRange.Zero, cleared.selection)
        assertEquals(null, cleared.composition)
        assertTrue(editing != cleared)
    }

    @Test
    fun query_change_immediately_clears_old_results_and_ignores_late_results() =
        runTest {
            val repo = ControlledSearchRepository()
            repo.emit("alpha", null, listOf(hit(1, "alpha result")))
            val vm = viewModel(repo)

            vm.state.test {
                awaitItem()
                // Settle on the key under test rather than counting emissions: a StateFlow conflates,
                // so the loading frame between a query change and its results is not guaranteed to be
                // observed. What this test protects is the pairing of query and results, which holds
                // whichever intermediate states survive.
                vm.onQueryChange("alpha")
                advanceTimeBy(250)
                runCurrent()
                val alphaResults = awaitStateWhere { it.rawQuery == "alpha" && it.groups.isNotEmpty() }
                assertEquals(1, alphaResults.groups.size)

                // Changing the query drops the previous answer at once: no state may show the new
                // query alongside the old query's hits.
                vm.onQueryChange("beta")
                runCurrent()
                val betaLoading = awaitStateWhere { it.rawQuery == "beta" }
                assertTrue(betaLoading.groups.isEmpty())

                // A late answer for the abandoned query must not surface at all.
                repo.emit("alpha", null, listOf(hit(1, "late alpha result")))
                runCurrent()
                expectNoEvents()

                advanceTimeBy(250)
                runCurrent()
                repo.emit("beta", null, listOf(hit(2, "beta result")))
                runCurrent()
                val betaResults = awaitStateWhere { it.rawQuery == "beta" && it.groups.isNotEmpty() }
                assertTrue(!betaResults.searching)
                assertEquals(
                    "beta result",
                    betaResults.groups
                        .single()
                        .hits
                        .single()
                        .message.text,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun scope_change_immediately_clears_old_results_and_ignores_late_scope_results() =
        runTest {
            val repo = ControlledSearchRepository()
            val vm = viewModel(repo)

            vm.state.test {
                awaitItem()
                vm.init(bufferId = 7L)
                runCurrent()
                // The blank state now also carries coverage, so settle on the key under test rather
                // than counting frames.
                awaitStateWhere { it.hasBufferScope && it.scope == SearchScope.CURRENT }
                vm.onScopeChange(SearchScope.ALL)
                runCurrent()
                awaitStateWhere { it.scope == SearchScope.ALL } // blank, global scope

                repo.emit("alpha", null, listOf(hit(1, "global alpha result")))
                vm.onQueryChange("alpha")
                runCurrent()
                advanceTimeBy(250)
                runCurrent()
                val globalResults =
                    awaitStateWhere {
                        it.rawQuery == "alpha" &&
                            it.scope == SearchScope.ALL &&
                            it.groups.isNotEmpty()
                    }
                assertEquals(
                    "global alpha result",
                    globalResults.groups
                        .single()
                        .hits
                        .single()
                        .message.text,
                )

                vm.onScopeChange(SearchScope.CURRENT)
                val currentLoading =
                    awaitStateWhere {
                        it.rawQuery == "alpha" &&
                            it.scope == SearchScope.CURRENT &&
                            it.searching &&
                            it.groups.isEmpty()
                    }
                assertTrue(currentLoading.groups.isEmpty())

                repo.emit("alpha", null, listOf(hit(1, "late global alpha result")))
                runCurrent()
                expectNoEvents()

                advanceTimeBy(250)
                runCurrent()
                repo.emit("alpha", 7L, listOf(hit(7, "current alpha result")))
                runCurrent()
                val currentResults =
                    awaitStateWhere {
                        it.rawQuery == "alpha" &&
                            it.scope == SearchScope.CURRENT &&
                            it.groups.isNotEmpty()
                    }
                assertTrue(!currentResults.searching)
                assertEquals(
                    "current alpha result",
                    currentResults.groups
                        .single()
                        .hits
                        .single()
                        .message.text,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun blank_state_discloses_coverage_before_the_first_keystroke() =
        runTest {
            val repo =
                FakeSearchRepository(
                    emptyList(),
                    coverage = SearchCoverage.BufferPartial(openGaps = 2, historyComplete = false),
                )
            val vm = viewModel(repo)

            vm.state.test {
                vm.init(bufferId = 7L)
                runCurrent()
                val blank = awaitStateWhere { it.hasBufferScope }
                assertEquals(SearchCoverage.BufferPartial(2, false), blank.coverage)
                assertTrue("no query ran", blank.groups.isEmpty())
                assertEquals("the disclosure must not require a DB search", 0, repo.calls.get())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun results_carry_coverage_and_the_raw_page_truncation_flag() =
        runTest {
            val repo =
                FakeSearchRepository(
                    listOf(hit(1, "coroutine builder")),
                    truncated = true,
                    coverage = SearchCoverage.BufferComplete,
                )
            val vm = viewModel(repo)

            vm.state.test {
                vm.onQueryChange("coroutine")
                runCurrent()
                advanceTimeBy(300)
                val results = awaitStateWhere { it.groups.isNotEmpty() }
                assertEquals(SearchCoverage.BufferComplete, results.coverage)
                assertTrue("the DAO cap must surface in state", results.truncated)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun truncation_survives_the_client_side_from_filter() =
        runTest {
            val repo =
                FakeSearchRepository(
                    listOf(hit(1, "coroutine builder", sender = "alice"), hit(2, "coroutines", sender = "bob")),
                    truncated = true,
                )
            val vm = viewModel(repo)

            vm.state.test {
                vm.onQueryChange("coroutine from:bob")
                runCurrent()
                advanceTimeBy(300)
                val results = awaitStateWhere { it.groups.isNotEmpty() }
                assertEquals(
                    "bob",
                    results.groups
                        .single()
                        .hits
                        .single()
                        .message.sender,
                )
                assertTrue("filtering fewer rows does not un-truncate the page", results.truncated)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun clear_immediately_removes_results_and_ignores_late_results() =
        runTest {
            val repo = ControlledSearchRepository()
            repo.emit("alpha", null, listOf(hit(1, "alpha result")))
            val vm = viewModel(repo)

            vm.state.test {
                awaitItem()
                // Settle on the keys under test rather than counting emissions: a StateFlow conflates,
                // so neither the loading frame nor any intermediate between it and the results is
                // guaranteed to be observed — or skipped — consistently.
                vm.onQueryChange("alpha")
                runCurrent()
                advanceTimeBy(250)
                runCurrent()
                assertEquals(1, awaitStateWhere { it.groups.isNotEmpty() }.groups.size)

                vm.onQueryChange("")
                runCurrent()
                assertEquals(
                    SearchUiState(coverage = SearchCoverage.DeviceOnly),
                    awaitStateWhere {
                        it.rawQuery.isEmpty() &&
                            it.groups.isEmpty() &&
                            it.coverage == SearchCoverage.DeviceOnly
                    },
                )

                repo.emit("alpha", null, listOf(hit(1, "late alpha result")))
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun matchingBufferRows_matches_displayName_case_insensitively_and_respects_the_limit() {
        val rows =
            listOf(
                chatListRow(1, "#MotdChat"),
                chatListRow(2, "#other"),
                chatListRow(3, "motd-fan (DM)", type = BufferType.QUERY),
                chatListRow(4, "#motd-archived", archived = true),
                chatListRow(5, "Server", type = BufferType.SERVER),
            )

        val matches = matchingBufferRows(rows, "motd")

        assertEquals(listOf(1L, 3L), matches.map(ChatListRow::bufferId))
    }

    @Test
    fun matchingBufferRows_blank_query_matches_nothing() {
        assertEquals(emptyList<ChatListRow>(), matchingBufferRows(listOf(chatListRow(1, "#motd")), " "))
    }

    @Test
    fun matchingBufferRows_is_capped_at_the_limit() {
        val rows = (1..10).map { chatListRow(it.toLong(), "#motd-$it") }

        assertEquals(BUFFER_MATCH_LIMIT, matchingBufferRows(rows, "motd").size)
    }

    @Test
    fun buffer_matches_populate_only_for_all_scope() =
        runTest {
            val repo = ControlledSearchRepository()
            repo.emit("motd", null, emptyList())
            val buffers = FakeBufferRepository(buffer(), chatList = listOf(chatListRow(9, "#motd")))
            val vm = viewModel(repo, buffers = buffers).also { it.init(BUFFER_ID) }

            vm.state.test {
                awaitStateWhere { it.hasBufferScope && it.scope == SearchScope.CURRENT }
                vm.onScopeChange(SearchScope.ALL)
                vm.onQueryChange("motd")
                runCurrent()
                advanceTimeBy(250)
                runCurrent()
                val allScope = awaitStateWhere { it.bufferMatches.isNotEmpty() }
                assertEquals(listOf(9L), allScope.bufferMatches.map(ChatListRow::bufferId))

                vm.onScopeChange(SearchScope.CURRENT)
                runCurrent()
                assertEquals(emptyList<ChatListRow>(), awaitStateWhere { it.scope == SearchScope.CURRENT }.bufferMatches)

                vm.onScopeChange(SearchScope.SERVER)
                runCurrent()
                assertEquals(emptyList<ChatListRow>(), awaitStateWhere { it.scope == SearchScope.SERVER }.bufferMatches)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
