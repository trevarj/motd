package io.github.trevarj.motd.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.SearchCoverage
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.ext.SOJU_SEARCH_MAX_LIMIT
import io.github.trevarj.motd.irc.ext.SearchRequest
import io.github.trevarj.motd.irc.ext.SearchResultKind
import io.github.trevarj.motd.irc.ext.SearchResultMessage
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Filter scope for the search screen. "current" is only offered when launched with a bufferId, and
 * "server" additionally requires a live client that negotiated `soju.im/search`.
 */
enum class SearchScope { ALL, CURRENT, SERVER }

/**
 * One transient server hit. There is deliberately no MessageEntity behind it: search results carry
 * no interval semantics, so they are never written to Room.
 */
data class ServerHitUi(
    val bufferId: Long,
    val sender: String,
    val text: String,
    val kind: SearchResultKind,
    /** 0 when the line carried no time tag; such a hit jumps by msgid alone. */
    val serverTime: Long,
    val msgid: String?,
)

enum class ServerSearchError { REJECTED, UNAVAILABLE }

sealed interface ServerSearchState {
    data object Idle : ServerSearchState

    data object Searching : ServerSearchState

    data class Results(
        val hits: List<ServerHitUi>,
        val truncated: Boolean,
    ) : ServerSearchState

    data class Failed(
        val error: ServerSearchError,
    ) : ServerSearchState
}

/** Result group: one buffer's hits under a header. */
data class SearchGroup(
    val bufferId: Long,
    val bufferDisplayName: String,
    val networkName: String,
    val bufferType: BufferType,
    val networkId: Long,
    val avatarOverrideModel: String?,
    val hits: List<SearchHit>,
)

data class SearchUiState(
    val rawQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    /** True when this screen was launched scoped to a buffer (enables the "current" chip). */
    val hasBufferScope: Boolean = false,
    /**
     * Channel/DM name matches ("smart" results): shown as a row above the message-content groups
     * below, so a query matching a room's name surfaces it even when no message matches. Populated
     * only for [SearchScope.ALL] searches.
     */
    val bufferMatches: List<ChatListRow> = emptyList(),
    val groups: List<SearchGroup> = emptyList(),
    val searching: Boolean = false,
    /** What the searched corpus covers for the active scope; null until the first emission. */
    val coverage: SearchCoverage? = null,
    /** True when the active result page hit its row cap. */
    val truncated: Boolean = false,
    /** True when this buffer's network can run a server-side SEARCH right now. */
    val serverSearchAvailable: Boolean = false,
    val server: ServerSearchState = ServerSearchState.Idle,
)

/**
 * Parsed query: the FTS text and an optional client-side `from:nick` sender filter.
 * Pure so it is trivially testable and keeps the ViewModel thin.
 */
data class ParsedQuery(
    val text: String,
    val fromNick: String?,
)

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

/** True when [raw] contains no FTS text or sender-only filter to resolve. */
fun isEmptySearchQuery(raw: String): Boolean = parseSearchQuery(raw).let { it.text.isBlank() && it.fromNick == null }

/** One logical result request. Results from an older key must never render under a newer one. */
private data class SearchKey(
    val rawQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val bufferId: Long? = null,
) {
    fun emptyState() =
        SearchUiState(
            rawQuery = rawQuery,
            scope = scope,
            hasBufferScope = bufferId != null,
        )

    fun loadingState() = emptyState().copy(searching = true)
}

private fun SearchKey.pendingState(): SearchUiState =
    when {
        scope == SearchScope.SERVER -> emptyState()
        isEmptySearchQuery(rawQuery) -> emptyState()
        else -> loadingState()
    }

private data class KeyedSearchState(
    val key: SearchKey,
    val state: SearchUiState,
)

/** Availability plus the transient server-search result, kept outside the keyed local pipeline. */
private data class ServerSection(
    val available: Boolean = false,
    val state: ServerSearchState = ServerSearchState.Idle,
)

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val searchRepository: SearchRepository,
        private val bufferRepository: BufferRepository,
        private val connectionManager: ConnectionManager,
    ) : ViewModel() {
        /**
         * State changes atomically by logical query and scope. This clears old results before any
         * debounce and blocks late emissions from superseded keys.
         */
        private val searchKey = MutableStateFlow(SearchKey())

        private val serverSection = MutableStateFlow(ServerSection())

        /** Canonical room the server scope targets; null until the buffer resolves. */
        private var serverBuffer: BufferEntity? = null
        private var availabilityJob: Job? = null
        private var serverJob: Job? = null

        fun init(bufferId: Long?) {
            searchKey.update { key ->
                val scoped = bufferId != null
                key.copy(
                    bufferId = bufferId,
                    scope = if (scoped) SearchScope.CURRENT else SearchScope.ALL,
                )
            }
            availabilityJob?.cancel()
            serverBuffer = null
            serverSection.value = ServerSection()
            if (bufferId == null) return
            availabilityJob =
                viewModelScope.launch {
                    combine(
                        bufferRepository.observeBuffer(bufferId),
                        connectionManager.connectionStates,
                    ) { buffer, _ -> buffer }.collect { buffer ->
                        serverBuffer = buffer
                        val available =
                            buffer != null &&
                                buffer.type != BufferType.SERVER &&
                                connectionManager.serverSearchAvailable(buffer.networkId)
                        if (!available && searchKey.value.scope == SearchScope.SERVER) {
                            searchKey.update { it.copy(scope = SearchScope.CURRENT) }
                            cancelServerSearch()
                        }
                        serverSection.update { it.copy(available = available) }
                    }
                }
        }

        fun onServerSearchSubmit() {
            val key = searchKey.value
            if (key.scope != SearchScope.SERVER) return
            val buffer =
                serverBuffer ?: run {
                    serverSection.update { it.copy(state = ServerSearchState.Failed(ServerSearchError.UNAVAILABLE)) }
                    return
                }
            val parsed = parseSearchQuery(key.rawQuery)
            val text = parsed.text.takeIf { it.isNotBlank() }
            if (text == null && parsed.fromNick == null) {
                cancelServerSearch()
                return
            }
            cancelServerSearch()
            serverSection.update { it.copy(state = ServerSearchState.Searching) }
            serverJob =
                viewModelScope.launch {
                    val outcome =
                        try {
                            connectionManager
                                .searchMessages(
                                    buffer.networkId,
                                    SearchRequest(
                                        target = buffer.name,
                                        text = text,
                                        from = parsed.fromNick,
                                        limit = SOJU_SEARCH_MAX_LIMIT,
                                    ),
                                )?.let { raw -> raw.toResults(buffer.id) }
                                ?: ServerSearchState.Failed(ServerSearchError.UNAVAILABLE)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (rejected: IrcCommandException) {
                            ServerSearchState.Failed(ServerSearchError.REJECTED)
                        } catch (
                            @Suppress("TooGenericExceptionCaught") failure: Exception,
                        ) {
                            ServerSearchState.Failed(ServerSearchError.UNAVAILABLE)
                        }
                    serverSection.update { it.copy(state = outcome) }
                }
        }

        private fun List<SearchResultMessage>.toResults(
            bufferId: Long,
        ): ServerSearchState.Results =
            ServerSearchState.Results(
                hits =
                    mapNotNull { hit ->
                        if (hit.serverTime == null && hit.msgid == null) {
                            null
                        } else {
                            ServerHitUi(
                                bufferId = bufferId,
                                sender = hit.sender,
                                text = hit.text,
                                kind = hit.kind,
                                serverTime = hit.serverTime ?: 0L,
                                msgid = hit.msgid,
                            )
                        }
                    }.sortedByDescending { it.serverTime },
                truncated = size >= SOJU_SEARCH_MAX_LIMIT,
            )

        private fun cancelServerSearch() {
            serverJob?.cancel()
            serverJob = null
            serverSection.update { it.copy(state = ServerSearchState.Idle) }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val localState: Flow<KeyedSearchState> =
            searchKey
                .flatMapLatest { key ->
                    val states =
                        when {
                            key.scope == SearchScope.SERVER -> {
                                flowOf(key.emptyState())
                            }

                            isEmptySearchQuery(key.rawQuery) -> {
                                val scopeId = if (key.scope == SearchScope.CURRENT) key.bufferId else null
                                searchRepository
                                    .coverage(scopeId)
                                    .map { coverage -> key.emptyState().copy(coverage = coverage) }
                            }

                            else -> {
                                keywordState(key)
                            }
                        }
                    states.map { state -> KeyedSearchState(key, state) }
                }

        private fun keywordState(key: SearchKey): Flow<SearchUiState> =
            flow {
                val parsed = parseSearchQuery(key.rawQuery)
                val scopeId = if (key.scope == SearchScope.CURRENT) key.bufferId else null
                emit(key.loadingState())
                delay(QUERY_DEBOUNCE_MS)
                combine(
                    searchRepository.search(parsed.text, scopeId),
                    searchRepository.coverage(scopeId),
                ) { result, coverage -> result to coverage }.collect { (result, coverage) ->
                    if (searchKey.value == key) {
                        val filtered =
                            parsed.fromNick?.let { nick ->
                                result.hits.filter { it.message.sender.equals(nick, ignoreCase = true) }
                            } ?: result.hits
                        emit(
                            key.emptyState().copy(
                                groups = groupHits(filtered),
                                coverage = coverage,
                                truncated = result.truncated,
                            ),
                        )
                    }
                }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val bufferMatches: Flow<Pair<SearchKey, List<ChatListRow>>> =
            searchKey.flatMapLatest { key ->
                val parsed = parseSearchQuery(key.rawQuery)
                if (key.scope != SearchScope.ALL || parsed.text.isBlank()) {
                    flowOf(key to emptyList())
                } else {
                    bufferRepository.observeChatList().map { rows -> key to matchingBufferRows(rows, parsed.text) }
                }
            }

        val state: StateFlow<SearchUiState> =
            combine(localState, serverSection, bufferMatches, searchKey) { keyed, server, match, current ->
                val (matchKey, matches) = match
                val local =
                    if (keyed.key == current) {
                        keyed.state
                    } else {
                        current.pendingState()
                    }
                local.copy(
                    bufferMatches =
                        if (matchKey == current) {
                            matches
                        } else {
                            emptyList()
                        },
                    serverSearchAvailable = server.available,
                    server = if (local.scope == SearchScope.SERVER) server.state else ServerSearchState.Idle,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchUiState(),
            )

        fun onQueryChange(q: String) {
            searchKey.update { it.copy(rawQuery = q) }
            cancelServerSearch()
        }

        fun onScopeChange(scope: SearchScope) {
            searchKey.update { it.copy(scope = scope) }
            cancelServerSearch()
        }

        private companion object {
            const val QUERY_DEBOUNCE_MS = 250L
        }
    }

/** Cap on the "smart" channel/DM match row: a handful of best fits, not a second results list. */
internal const val BUFFER_MATCH_LIMIT = 5

/**
 * Room-name matches for the "smart" results row: non-archived, non-server rows whose display name
 * contains [query] (case-insensitive substring — this is name matching, not FTS). Pure and ordered
 * by the chat list's own order (already pinned/recency-sorted), so no extra ranking logic is needed
 * here.
 */
internal fun matchingBufferRows(
    rows: List<ChatListRow>,
    query: String,
    limit: Int = BUFFER_MATCH_LIMIT,
): List<ChatListRow> {
    if (query.isBlank()) return emptyList()
    return rows
        .asSequence()
        .filter { it.type != BufferType.SERVER && !it.archived }
        .filter { it.displayName.contains(query, ignoreCase = true) }
        .take(limit)
        .toList()
}

/** Group hits by buffer, preserving overall recency order (hits already time-ordered by DAO). */
fun groupHits(hits: List<SearchHit>): List<SearchGroup> =
    hits
        .groupBy { it.message.bufferId }
        .map { (bufferId, groupHits) ->
            val first = groupHits.first()
            SearchGroup(
                bufferId = bufferId,
                bufferDisplayName = first.bufferDisplayName,
                networkName = first.networkName,
                bufferType = first.bufferType,
                networkId = first.networkId,
                avatarOverrideModel = first.avatarOverrideModel,
                hits = groupHits,
            )
        }
