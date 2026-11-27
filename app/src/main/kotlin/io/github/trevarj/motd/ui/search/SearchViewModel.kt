package io.github.trevarj.motd.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.LocalSearchFilters
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

/** Free text and exact structured filters, parsed before querying either corpus. */
data class ParsedQuery(
    val text: String,
    val filters: LocalSearchFilters,
) {
    val isEmpty: Boolean get() = text.isBlank() && filters.isEmpty

    // The wire targets only the current IRC conversation and supports at most one sender.
    val requiresLocalSearch: Boolean
        get() = filters.room != null || filters.network != null || filters.senders.size > 1

    val matchesBufferNames: Boolean get() = filters.isEmpty && text.isNotBlank()
}

fun parseSearchQuery(raw: String): ParsedQuery {
    var room: String? = null
    var network: String? = null
    val senders = mutableListOf<String>()
    val text = StringBuilder()
    var end = 0
    while (end < raw.length) {
        if (raw[end].isWhitespace()) {
            end++
            continue
        }
        val start = end
        // Unknown qualifier-shaped prefixes still protect their quoted values as literal text.
        while (end < raw.length && (raw[end].isLetterOrDigit() || raw[end] == '_' || raw[end] == '-')) end++
        val hasPrefix = raw[start].isLetter() && raw.getOrNull(end) == ':'
        val prefixLength = end - start
        val qualifier =
            when {
                !hasPrefix || prefixLength !in 2..4 -> ""
                (start until end).any { raw[it] !in 'A'..'Z' && raw[it] !in 'a'..'z' } -> ""
                prefixLength == 2 && raw.regionMatches(start, "in", 0, 2, ignoreCase = true) -> "in"
                prefixLength == 2 && raw.regionMatches(start, "on", 0, 2, ignoreCase = true) -> "on"
                prefixLength == 4 && raw.regionMatches(start, "from", 0, 4, ignoreCase = true) -> "from"
                else -> ""
            }
        val valueStart = if (hasPrefix) end + 1 else start
        if (hasPrefix) end = valueStart
        val quoted = raw.getOrNull(valueStart) == '"'
        var argument: String? = null
        if (quoted) {
            val value = if (qualifier == "in" || qualifier == "on") StringBuilder() else null
            end = valueStart + 1
            while (end < raw.length && raw[end] != '"') {
                // Only quotes and backslashes are escapable; other backslashes are literal.
                if (raw[end] == '\\' && (raw.getOrNull(end + 1) == '"' || raw.getOrNull(end + 1) == '\\')) end++
                value?.append(raw[end])
                end++
            }
            if (end < raw.length) {
                end++
                // A closing quote must end the token, or the whole token remains literal.
                if (end == raw.length || raw[end].isWhitespace()) argument = value?.toString()
            }
        }
        while (end < raw.length && !raw[end].isWhitespace()) end++
        if (!quoted && qualifier.isNotEmpty()) {
            argument = raw.substring(valueStart, end).takeUnless { '"' in it }
        }
        if (!argument.isNullOrBlank()) {
            when {
                qualifier == "in" && room == null -> {
                    room = argument
                    continue
                }

                qualifier == "on" && network == null -> {
                    network = argument
                    continue
                }

                qualifier == "from" -> {
                    if (argument !in senders) senders += argument
                    continue
                }
            }
        }
        if (text.isNotEmpty()) text.append(' ')
        text.append(raw, start, end)
    }
    return ParsedQuery(text.toString(), LocalSearchFilters(room, network, senders))
}

/** One logical result request. Results from an older key must never render under a newer one. */
private data class SearchKey(
    val rawQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val bufferId: Long? = null,
) {
    val parsed = parseSearchQuery(rawQuery)
    val usesServer: Boolean get() = scope == SearchScope.SERVER && !parsed.requiresLocalSearch
    val localBufferId: Long? get() = bufferId.takeUnless { scope == SearchScope.ALL }

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
        usesServer || parsed.isEmpty -> emptyState()
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
    val key: SearchKey? = null,
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
            if (!key.usesServer) return
            val buffer =
                serverBuffer ?: run {
                    serverSection.update { it.copy(key = key, state = ServerSearchState.Failed(ServerSearchError.UNAVAILABLE)) }
                    return
                }
            val text = key.parsed.text.takeIf { it.isNotBlank() }
            if (key.parsed.isEmpty) {
                cancelServerSearch()
                return
            }
            cancelServerSearch()
            val generation = ++serverGeneration
            serverSection.update { it.copy(key = key, state = ServerSearchState.Searching) }
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
                                        from =
                                            key.parsed.filters.senders
                                                .singleOrNull(),
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
            serverSection.update { it.copy(key = null, state = ServerSearchState.Idle) }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val localState: Flow<KeyedSearchState> =
            searchKeys
                .flatMapLatest { key ->
                    val states =
                        when {
                            key.usesServer -> {
                                flowOf(key.emptyState())
                            }

                            key.parsed.isEmpty -> {
                                searchRepository
                                    .coverage(key.localBufferId)
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
                val parsed = key.parsed
                val scopeId = key.localBufferId
                emit(key.loadingState())
                delay(QUERY_DEBOUNCE_MS)
                combine(
                    searchRepository.search(parsed.text, scopeId, parsed.filters),
                    searchRepository.coverage(scopeId),
                ) { result, coverage -> result to coverage }.collect { (result, coverage) ->
                    if (searchInput.value.key == key) {
                        emit(
                            key.emptyState().copy(
                                groups = groupHits(result.hits),
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
                val query = key.parsed.text
                if (key.scope != SearchScope.ALL || !key.parsed.matchesBufferNames) {
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
                    server = if (current.usesServer && server.key == current) server.state else ServerSearchState.Idle,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchUiState(),
            )

        fun setBufferMatchesExpanded(expanded: Boolean) {
            searchInput.update { input ->
                if (input.bufferMatchesExpanded == expanded) return@update input
                if (!expanded || (input.key.scope == SearchScope.ALL && input.key.parsed.matchesBufferNames)) {
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
            if (scope != SearchScope.ALL && searchInput.value.key.bufferId == null) return
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
