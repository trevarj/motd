package io.github.trevarj.motd.ui.search

import app.cash.turbine.test
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.repo.SearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Query pipeline: blank queries clear results immediately, non-blank queries are debounced before
 * hitting the FTS repo (so rapid typing launches one query, not one per keystroke), and results
 * still flow through and group by buffer. The visible TextField value is now local Compose state
 * (not unit-testable), so this only exercises the ViewModel's results/debounce seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private fun hit(bufferId: Long, text: String, sender: String = "alice") = SearchHit(
        message = MessageEntity(
            id = bufferId, bufferId = bufferId, serverTime = 1_000L,
            sender = sender, kind = MessageKind.PRIVMSG, text = text, dedupKey = "k$bufferId",
        ),
        bufferDisplayName = "#kotlin", networkName = "Libera",
    )

    /** Counts search() invocations so we can assert the debounce collapses rapid keystrokes. */
    private class FakeSearchRepository(
        private val result: List<SearchHit>,
        val calls: AtomicInteger = AtomicInteger(0),
    ) : SearchRepository {
        override fun search(query: String, bufferId: Long?): Flow<List<SearchHit>> {
            calls.incrementAndGet()
            return flowOf(result)
        }
    }

    @Test
    fun blank_query_emits_empty_results_without_hitting_the_repo() = runTest {
        val repo = FakeSearchRepository(emptyList())
        val vm = SearchViewModel(repo)

        vm.state.test {
            assertEquals(SearchUiState(), awaitItem()) // initial
            vm.onQueryChange("   ")
            runCurrent()
            assertTrue("blank query yields no result groups", awaitItem().groups.isEmpty())
            assertEquals("blank query must not query the DB", 0, repo.calls.get())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun rapid_typing_is_debounced_into_a_single_db_query() = runTest {
        val repo = FakeSearchRepository(listOf(hit(1, "coroutine builder")))
        val vm = SearchViewModel(repo)

        vm.state.test {
            awaitItem() // initial

            // Simulate keystrokes faster than the debounce window.
            vm.onQueryChange("c")
            advanceTimeBy(50)
            vm.onQueryChange("co")
            advanceTimeBy(50)
            vm.onQueryChange("cor")
            advanceTimeBy(50)
            vm.onQueryChange("coroutine")
            // Not yet past the debounce window: no query should have fired.
            runCurrent()
            assertEquals("no DB hit before the typing pause", 0, repo.calls.get())

            // Past the debounce window: exactly one query fires and results arrive.
            advanceTimeBy(300)
            val results = awaitItem()
            assertEquals("rapid typing collapses to one DB query", 1, repo.calls.get())
            assertEquals("coroutine", results.rawQuery)
            assertEquals(1, results.groups.size)
            assertEquals(1L, results.groups.first().bufferId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clear_resets_selection_and_composition() {
        val editing = TextFieldValue(
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
    fun clearing_query_suppresses_late_results_from_cancelled_search() = runTest {
        val pending = MutableStateFlow<List<SearchHit>>(emptyList())
        val repo = object : SearchRepository {
            override fun search(query: String, bufferId: Long?): Flow<List<SearchHit>> = pending
        }
        val vm = SearchViewModel(repo)

        vm.state.test {
            awaitItem()
            vm.onQueryChange("coroutine")
            advanceTimeBy(300)
            runCurrent()
            assertEquals("coroutine", awaitItem().rawQuery)

            vm.onQueryChange("")
            runCurrent()
            assertEquals(SearchUiState(), awaitItem())

            pending.value = listOf(hit(1, "late coroutine result"))
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
