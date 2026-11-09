package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.SearchHit
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
// so we neutralize by removal rather than quoting. Empty input yields no results rather than a
// malformed MATCH.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchRepositoryImpl
    @Inject
    constructor(
        private val bufferDao: BufferDao,
        private val messageDao: MessageDao,
        private val historyGapDao: HistoryGapDao,
        private val settings: SettingsRepository,
    ) : SearchRepository {
        override val visibility: Flow<MessageVisibilitySpec> = settings.settings.map(MessageVisibilitySpec::from).distinctUntilChanged()

        override fun search(
            query: String,
            bufferId: Long?,
        ): Flow<LocalSearchResult> {
            val match = sanitizeFtsQuery(query)
            if (match.isEmpty()) return flowOf(LocalSearchResult(emptyList(), truncated = false))
            val hits =
                if (bufferId == null) {
                    messageDao.search(match, null)
                } else {
                    bufferDao.observe(bufferId).flatMapLatest { room ->
                        messageDao.search(match, room?.id ?: bufferId)
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
            /**
             * Raw FTS page size. MUST equal the `LIMIT` literal in `MessageDao.search`; Room cannot
             * bind a limit into that query, so a Robolectric test pins the two together.
             */
            const val LOCAL_SEARCH_LIMIT = 200

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
