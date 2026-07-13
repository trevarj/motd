package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// FTS4 search. User input is sanitized into a safe MATCH expression: each whitespace-delimited
// token is stripped of FTS operator characters (quotes, *, ^, -, :, parens) and given a bare `*`
// suffix for prefix search, tokens joined by spaces (implicit AND). A bare `token*` is the only
// form SQLite FTS4 treats as a prefix query — a quoted `"token"*` silently drops the wildcard —
// so we neutralize by removal rather than quoting. Empty input yields no results rather than a
// malformed MATCH.
class SearchRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val settings: SettingsRepository,
) : SearchRepository {
    override fun search(query: String, bufferId: Long?): Flow<List<SearchHit>> {
        val match = sanitizeFtsQuery(query)
        if (match.isEmpty()) return flowOf(emptyList())
        return combine(
            messageDao.search(match, bufferId),
            settings.settings.map(MessageVisibilitySpec::from).distinctUntilChanged(),
        ) { hits, spec ->
            val policy = MessageVisibilityPolicy(spec)
            hits.filter { policy.search(it.message) }
        }
    }

    companion object {
        // FTS4 syntactic operator chars that must not leak into a MATCH token.
        private val FTS_SPECIAL = Regex("[\"*^:()\\-]")

        fun sanitizeFtsQuery(raw: String): String =
            raw.trim()
                .split(Regex("\\s+"))
                .map { it.replace(FTS_SPECIAL, "") }
                .filter { it.isNotBlank() }
                .joinToString(" ") { token -> "$token*" }
    }
}
