package io.github.trevarj.motd.agentwire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A session this channel was attached to before, remembered per channel for fast switching. */
@Serializable
data class AgentwireRecentSession(
    val sid: String,
    val title: String,
    val cwd: String? = null,
    val backend: String? = null,
)

enum class AgentwireDrawerSection { BOUND, LIVE, RECENT }

enum class AgentwireDrawerStatus { WORKING, BLOCKED, IDLE }

data class AgentwireDrawerRow(
    val sid: String,
    val title: String,
    val backend: String?,
    val cwd: String?,
    /** Last path segment of [cwd]; the full path stays available as the secondary line. */
    val directory: String?,
    val status: AgentwireDrawerStatus,
    val tuiAttached: Boolean,
    val attached: Boolean,
    val section: AgentwireDrawerSection,
    /** Non-terminal subagents of this session; only the bound row can know this. */
    val activeSubagents: Int = 0,
)

internal const val AGENTWIRE_RECENT_SESSION_LIMIT = 10

private val recentsJson = Json { ignoreUnknownKeys = true }

internal fun decodeAgentwireRecents(raw: String?): List<AgentwireRecentSession> = if (raw.isNullOrBlank()) {
    emptyList()
} else {
    runCatching { recentsJson.decodeFromString<List<AgentwireRecentSession>>(raw) }.getOrDefault(emptyList())
}

internal fun encodeAgentwireRecents(sessions: List<AgentwireRecentSession>): String =
    recentsJson.encodeToString(sessions)

/** Newest first, one entry per sid, bounded so the stored preference cannot grow without limit. */
internal fun agentwireRecentsWith(
    current: List<AgentwireRecentSession>,
    session: AgentwireRecentSession,
): List<AgentwireRecentSession> = (listOf(session) + current.filterNot { it.sid == session.sid })
    .take(AGENTWIRE_RECENT_SESSION_LIMIT)

/**
 * Drawer rows: the bound session first, then live sessions, then remembered sessions that are
 * neither. Deduplicated by sid, so the first section that claims a session owns it.
 */
internal fun agentwireDrawerRows(state: AgentwireUiState): List<AgentwireDrawerRow> {
    val rows = LinkedHashMap<String, AgentwireDrawerRow>()
    fun add(sid: String, item: AgentwireListItem?, section: AgentwireDrawerSection) {
        if (sid in rows) return
        rows[sid] = drawerRow(
            state = state,
            sid = sid,
            item = item ?: state.liveSessions.firstOrNull { it.id == sid },
            recent = state.recentSessions.firstOrNull { it.sid == sid },
            section = section,
        )
    }
    state.activeSid?.let { add(it, null, AgentwireDrawerSection.BOUND) }
    state.liveSessions.forEach { add(it.id, it, AgentwireDrawerSection.LIVE) }
    state.recentSessions.forEach { add(it.sid, null, AgentwireDrawerSection.RECENT) }
    return rows.values.toList()
}

private fun drawerRow(
    state: AgentwireUiState,
    sid: String,
    item: AgentwireListItem?,
    recent: AgentwireRecentSession?,
    section: AgentwireDrawerSection,
): AgentwireDrawerRow {
    val attached = sid == state.activeSid
    // The pushed status registry is newer than any `session.page` snapshot, so it wins per field.
    val status = state.sessionStatuses[sid]
    val busy = status?.busy ?: item?.raw?.bool("busy") ?: false
    val flags = status?.flags ?: item?.raw?.stringList("flags").orEmpty()
    val blocked = flags.any { it.startsWith("waiting", ignoreCase = true) } ||
        state.requests.any { it.sid == sid }
    val cwd = status?.cwd ?: item?.subtitle ?: recent?.cwd ?: state.cwd.takeIf { attached }
    return AgentwireDrawerRow(
        sid = sid,
        title = item?.title ?: recent?.title ?: sid.take(12),
        backend = item?.raw?.string("backend") ?: recent?.backend ?: state.backend,
        cwd = cwd,
        directory = cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: cwd,
        status = when {
            blocked -> AgentwireDrawerStatus.BLOCKED
            busy -> AgentwireDrawerStatus.WORKING
            else -> AgentwireDrawerStatus.IDLE
        },
        tuiAttached = status?.tuiAttached ?: item?.raw?.bool("tuiAttached") ?: false,
        attached = attached,
        section = section,
        // `subagent.updated` is bound-session state, so no other row can carry a count.
        activeSubagents = if (attached) state.subagents.count { !it.terminal } else 0,
    )
}
