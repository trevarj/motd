package io.github.trevarj.motd.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.repo.SearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Filter scope for the search screen. "current" only offered when launched with a bufferId. */
enum class SearchScope { ALL, CURRENT }

/** Result group: one buffer's hits under a header. */
data class SearchGroup(
    val bufferId: Long,
    val bufferDisplayName: String,
    val networkName: String,
    val hits: List<SearchHit>,
)

data class SearchUiState(
    val rawQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    /** True when this screen was launched scoped to a buffer (enables the "current" chip). */
    val hasBufferScope: Boolean = false,
    val groups: List<SearchGroup> = emptyList(),
    val searching: Boolean = false,
)

/**
 * Parsed query: the FTS text and an optional client-side `from:nick` sender filter.
 * Pure so it is trivially testable and keeps the ViewModel thin.
 */
data class ParsedQuery(val text: String, val fromNick: String?)

fun parseSearchQuery(raw: String): ParsedQuery {
    val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    var fromNick: String? = null
    val rest = mutableListOf<String>()
    for (t in tokens) {
        val lower = t.lowercase()
        if (lower.startsWith("from:") && t.length > 5) {
            fromNick = t.substring(5)
        } else {
            rest.add(t)
        }
    }
    return ParsedQuery(text = rest.joinToString(" "), fromNick = fromNick)
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val scope = MutableStateFlow(SearchScope.ALL)

    /** Set once from nav args; drives the "current buffer" chip availability + scoping. */
    private var bufferId: Long? = null
    private val hasBufferScope = MutableStateFlow(false)

    fun init(bufferId: Long?) {
        this.bufferId = bufferId
        hasBufferScope.value = bufferId != null
        if (bufferId != null) scope.value = SearchScope.CURRENT
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SearchUiState> =
        combine(query, scope, hasBufferScope) { q, sc, hasScope -> Triple(q, sc, hasScope) }
            .flatMapLatest { (q, sc, hasScope) ->
                val parsed = parseSearchQuery(q)
                if (parsed.text.isBlank() && parsed.fromNick == null) {
                    flowOf(SearchUiState(rawQuery = q, scope = sc, hasBufferScope = hasScope))
                } else {
                    val scopeId = if (sc == SearchScope.CURRENT) bufferId else null
                    searchRepository.search(parsed.text, scopeId).map { hits ->
                        // `from:nick` is a client-side sender filter on top of the FTS results.
                        val filtered = parsed.fromNick?.let { nick ->
                            hits.filter { it.message.sender.equals(nick, ignoreCase = true) }
                        } ?: hits
                        SearchUiState(
                            rawQuery = q,
                            scope = sc,
                            hasBufferScope = hasScope,
                            groups = groupHits(filtered),
                            searching = false,
                        )
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchUiState(),
            )

    fun onQueryChange(q: String) { query.value = q }

    fun onScopeChange(s: SearchScope) { scope.value = s }
}

/** Group hits by buffer, preserving overall recency order (hits already time-ordered by DAO). */
fun groupHits(hits: List<SearchHit>): List<SearchGroup> =
    hits.groupBy { it.message.bufferId }
        .map { (bufferId, groupHits) ->
            val first = groupHits.first()
            SearchGroup(
                bufferId = bufferId,
                bufferDisplayName = first.bufferDisplayName,
                networkName = first.networkName,
                hits = groupHits,
            )
        }
