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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
    val identityRules: io.github.trevarj.motd.irc.proto.IrcIdentityRules =
        io.github.trevarj.motd.irc.proto
            .IrcIdentityRules(),
    val normalizedActor: String = identityRules.normalize(sender),
    val isSelf: Boolean = false,
    val senderAccount: String? = null,
)

enum class ServerSearchError { REJECTED, UNAVAILABLE }

sealed interface ServerSearchState {
    data object Idle : ServerSearchState

    data object Searching : ServerSearchState

    data class Results(
        val hits: List<ServerHitUi>,
        val truncated: Boolean,
        val generation: Long,
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
     * Local channel/DM name matches, only for [SearchScope.ALL]. The preview includes up to
     * [BUFFER_MATCH_LIMIT] visible rows plus one overflow sentinel; the expanded projection has
     * no cap. Both follow the chat list's order.
     */
    val bufferMatches: List<ChatListRow> = emptyList(),
    /** Whether [bufferMatches] is the ready, uncapped projection, even when it is empty. */
    val bufferMatchesExpanded: Boolean = false,
    val groups: List<SearchGroup> = emptyList(),
    val searching: Boolean = false,
    /** What the searched corpus covers for the active scope; null until the first emission. */
    val coverage: SearchCoverage? = null,
    /** True when the active result page hit its row cap. */
    val truncated: Boolean = false,
    /** True when this buffer's network can run a server-side SEARCH right now. */
    val serverSearchAvailable: Boolean = false,
    val server: ServerSearchState = ServerSearchState.Idle,
    val visibility: io.github.trevarj.motd.data.visibility.MessageVisibilitySpec =
        io.github.trevarj.motd.data.visibility
            .MessageVisibilitySpec(),
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

private data class SearchInput(
    val key: SearchKey = SearchKey(),
    val bufferMatchesExpanded: Boolean = false,
)

private data class KeyedBufferMatches(
    val key: SearchKey,
    val matches: List<ChatListRow> = emptyList(),
    val expanded: Boolean = false,
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
        private val searchInput = MutableStateFlow(SearchInput())
        private val searchKeys = searchInput.map { it.key }.distinctUntilChanged()

        private val serverSection = MutableStateFlow(ServerSection())

        /** Canonical room the server scope targets; null until the buffer resolves. */
        private var serverBuffer: BufferEntity? = null
        private var availabilityJob: Job? = null
        private var serverJob: Job? = null
        private var serverGeneration = 0L

        fun init(bufferId: Long?) {
            cancelServerSearch()
            updateSearchKey { key ->
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
                        if (serverBuffer?.id != buffer?.id) cancelServerSearch()
                        serverBuffer = buffer
                        val available =
                            buffer != null &&
                                buffer.type != BufferType.SERVER &&
                                connectionManager.serverSearchAvailable(buffer.networkId)
                        if (!available && searchInput.value.key.scope == SearchScope.SERVER) {
                            updateSearchKey { it.copy(scope = SearchScope.CURRENT) }
                            cancelServerSearch()
                        }
                        serverSection.update { it.copy(available = available) }
                    }
                }
        }

        fun onServerSearchSubmit() {
            val key = searchInput.value.key
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
            val generation = ++serverGeneration
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
                                )?.let { raw -> raw.toResults(buffer, generation) }
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
                    if (serverGeneration == generation && searchInput.value.key == key && serverBuffer?.id == buffer.id) {
                        serverSection.update { it.copy(state = outcome) }
                    }
                }
        }

        private fun List<SearchResultMessage>.toResults(
            buffer: BufferEntity,
            generation: Long,
        ): ServerSearchState.Results {
            val rules =
                connectionManager.clientFor(buffer.networkId)?.isupport?.identityRules ?: io.github.trevarj.motd.irc.proto
                    .IrcIdentityRules()
            val self = (connectionManager.connectionStates.value[buffer.networkId] as? io.github.trevarj.motd.irc.event.IrcClientState.Ready)?.nick?.let(rules::normalize)
            return ServerSearchState.Results(
                generation = generation,
                hits =
                    map { hit ->
                        ServerHitUi(
                            bufferId = buffer.id,
                            normalizedActor = rules.normalize(hit.sender),
                            identityRules = rules,
                            isSelf = self != null && self == rules.normalize(hit.sender),
                            sender = hit.sender,
                            senderAccount = hit.senderAccount,
                            text = hit.text,
                            kind = hit.kind,
                            serverTime = hit.serverTime ?: 0L,
                            msgid = hit.msgid,
                        )
                    }.sortedByDescending { it.serverTime },
                truncated = size >= SOJU_SEARCH_MAX_LIMIT,
            )
        }

        private fun cancelServerSearch() {
            serverGeneration++
            serverJob?.cancel()
            serverJob = null
            serverSection.update { it.copy(state = ServerSearchState.Idle) }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val localState: Flow<KeyedSearchState> =
            searchKeys
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
                    if (searchInput.value.key == key) {
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
        private val bufferMatches: Flow<KeyedBufferMatches> =
            searchKeys.flatMapLatest { key ->
                val query = parseSearchQuery(key.rawQuery).text
                if (key.scope != SearchScope.ALL || query.isBlank()) {
                    flowOf(KeyedBufferMatches(key))
                } else {
                    combine(
                        bufferRepository.observeChatList(),
                        searchInput.filter { it.key == key }.map { it.bufferMatchesExpanded }.distinctUntilChanged(),
                    ) { rows, expanded ->
                        val limit = if (expanded) null else BUFFER_MATCH_LIMIT + 1
                        KeyedBufferMatches(key, matchingBufferRows(rows, query, limit), expanded)
                    }.onStart { emit(KeyedBufferMatches(key)) }
                }
            }

        val state: StateFlow<SearchUiState> =
            combine(localState, serverSection, bufferMatches, searchInput, searchRepository.visibility) { keyed, server, match, input, visibility ->
                val current = input.key
                val local =
                    if (keyed.key == current) {
                        keyed.state
                    } else {
                        current.pendingState()
                    }
                local.copy(
                    bufferMatches =
                        when {
                            match.key != current -> emptyList()
                            !input.bufferMatchesExpanded && match.matches.size > BUFFER_MATCH_LIMIT + 1 -> match.matches.take(BUFFER_MATCH_LIMIT + 1)
                            else -> match.matches
                        },
                    bufferMatchesExpanded = match.key == current && match.expanded && input.bufferMatchesExpanded,
                    visibility = visibility,
                    serverSearchAvailable = server.available,
                    server = if (local.scope == SearchScope.SERVER) server.state else ServerSearchState.Idle,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchUiState(),
            )

        fun setBufferMatchesExpanded(expanded: Boolean) {
            searchInput.update { input ->
                if (input.bufferMatchesExpanded == expanded) return@update input
                if (!expanded || (input.key.scope == SearchScope.ALL && parseSearchQuery(input.key.rawQuery).text.isNotBlank())) {
                    input.copy(bufferMatchesExpanded = expanded)
                } else {
                    input
                }
            }
        }

        private fun updateSearchKey(transform: (SearchKey) -> SearchKey) {
            searchInput.update { input ->
                val key = transform(input.key)
                if (key == input.key) input else SearchInput(key)
            }
        }

        fun onQueryChange(q: String) {
            updateSearchKey { it.copy(rawQuery = q) }
            cancelServerSearch()
        }

        fun onScopeChange(scope: SearchScope) {
            updateSearchKey { it.copy(scope = scope) }
            cancelServerSearch()
        }

        private companion object {
            const val QUERY_DEBOUNCE_MS = 250L
        }
    }

/** Number of visible channel/DM chips in the preview row. */
internal const val BUFFER_MATCH_LIMIT = 5

/**
 * Local channel/DM name matches: non-archived, non-server rows whose display name contains
 * [query] (case-insensitive substring — this is name matching, not FTS). Preserves the chat
 * list's order (already pinned/recency-sorted). A null [limit] returns all matches.
 */
internal fun matchingBufferRows(
    rows: List<ChatListRow>,
    query: String,
    limit: Int? = BUFFER_MATCH_LIMIT,
): List<ChatListRow> {
    if (query.isBlank()) return emptyList()
    val matches =
        rows
            .asSequence()
            .filter { it.type != BufferType.SERVER && !it.archived }
            .filter { it.displayName.contains(query, ignoreCase = true) }
    return (if (limit == null) matches else matches.take(limit)).toList()
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
