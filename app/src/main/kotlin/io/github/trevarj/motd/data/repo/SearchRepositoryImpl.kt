package io.github.trevarj.motd.data.repo

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.LOCAL_MESSAGE_SEARCH_LIMIT
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.db.filteredMessageSearchQuery
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// FTS4 search. User input is sanitized into a safe MATCH expression: each whitespace-delimited
// token is stripped of FTS operator characters (quotes, *, ^, -, :, parens) and given a bare `*`
// suffix for prefix search, tokens joined by spaces (implicit AND). A bare `token*` is the only
// form SQLite FTS4 treats as a prefix query — a quoted `"token"*` silently drops the wildcard —
// so we neutralize by removal rather than quoting. Filter-only searches never use MATCH.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchRepositoryImpl
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val settings: SettingsRepository,
    ) : SearchRepository {
        private val bufferDao = db.bufferDao()
        private val historyGapDao = db.historyGapDao()
        private val messageDao = db.messageDao()
        private val networkDao = db.networkDao()

        override val visibility: Flow<MessageVisibilitySpec> = settings.settings.map(MessageVisibilitySpec::from).distinctUntilChanged()

        override fun search(
            query: String,
            bufferId: Long?,
            filters: LocalSearchFilters,
        ): Flow<LocalSearchResult> {
            val match = sanitizeFtsQuery(query)
            if (match.isEmpty() && (query.isNotBlank() || filters.isEmpty)) {
                return flowOf(LocalSearchResult(emptyList(), truncated = false))
            }

            fun filteredQuery(networkNames: Set<String>?) = filteredMessageSearchQuery(match, bufferId, filters.room, networkNames, filters.senders)

            val hits: Flow<List<SearchHit>> =
                if (filters.isEmpty) {
                    messageDao.search(match, bufferId)
                } else {
                    val invalidations =
                        db.invalidationTracker.createFlow(
                            "messages",
                            "messages_fts",
                            "buffers",
                            "networks",
                            "network_identity",
                            "room_aliases",
                        )
                    val label = filters.network
                    if (label == null) {
                        val sql = filteredQuery(null)
                        invalidations.map { messageDao.searchFiltered(sql) }
                    } else {
                        invalidations.map {
                            db.withTransaction {
                                // SQLite NOCASE is ASCII-only. Read the exact Unicode label and
                                // capped page in one snapshot, including case-only label changes.
                                val names = networkDao.names().filterTo(HashSet()) { it.equals(label, ignoreCase = true) }
                                if (names.isEmpty()) emptyList() else messageDao.searchFiltered(filteredQuery(names))
                            }
                        }
                    }
                }
            return combine(
                hits,
                visibility,
            ) { hits, spec ->
                // Truncation is a property of the SQL page, not of what survives visibility rules:
                // a fully hidden 200-row page still means older matches exist beyond the cap.
                val truncated = hits.size >= LOCAL_SEARCH_LIMIT
                val policies = mutableMapOf<Pair<String?, String?>, MessageVisibilityPolicy>()
                val visible =
                    hits.filter { hit ->
                        val identity = hit.caseMapping to hit.chanTypes
                        val policy =
                            policies.getOrPut(identity) {
                                MessageVisibilityPolicy(
                                    spec,
                                    IrcIdentityRules.from(hit.caseMapping, hit.chanTypes),
                                )
                            }
                        policy.search(hit.message)
                    }
                LocalSearchResult(visible, truncated)
            }
        }

        override fun coverage(bufferId: Long?): Flow<SearchCoverage> {
            // The all-buffers scope spans rooms with independent, unknowable history states; the only
            // claim that is always true is that nothing beyond this device was searched.
            if (bufferId == null) return flowOf(SearchCoverage.DeviceOnly)
            return bufferDao.observe(bufferId).flatMapLatest { room ->
                // Mirror search()'s redirect resolution so coverage describes the rows actually queried.
                val canonicalId = room?.id ?: bufferId
                if (room == null) {
                    // Unknown room: never overclaim completeness for a scope we cannot inspect.
                    flowOf(SearchCoverage.BufferPartial(openGaps = 0, historyComplete = false))
                } else {
                    historyGapDao.observeCount(canonicalId).map { openGaps ->
                        if (room.historyComplete && openGaps == 0) {
                            SearchCoverage.BufferComplete
                        } else {
                            SearchCoverage.BufferPartial(openGaps, room.historyComplete)
                        }
                    }
                }
            }
        }

        companion object {
            const val LOCAL_SEARCH_LIMIT = LOCAL_MESSAGE_SEARCH_LIMIT

            // FTS4 syntactic operator chars that must not leak into a MATCH token.
            private val FTS_SPECIAL = Regex("[\"*^:()\\-]")

            fun sanitizeFtsQuery(raw: String): String =
                raw
                    .trim()
                    .split(Regex("\\s+"))
                    .map { it.replace(FTS_SPECIAL, "") }
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { token -> "$token*" }
        }
    }
