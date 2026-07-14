package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.ChatListRow

internal data class MonitorSelection(
    val selected: List<String>,
    val allDesired: List<String>,
)

internal fun selectMonitorTargets(
    friends: Set<String>,
    queryRows: List<ChatListRow>,
    limit: Int?,
    normalize: (String) -> String,
): MonitorSelection {
    val seen = HashSet<String>()
    val friendTargets = friends
        .sortedBy(normalize)
        .filter { seen.add(normalize(it)) }
    val queryTargets = queryRows
        .sortedWith(
            compareByDescending<ChatListRow> { it.pinned }
                .thenByDescending { it.lastMessageTime ?: Long.MIN_VALUE }
                .thenBy { normalize(it.displayName) },
        )
        .map { it.displayName }
        .filter { seen.add(normalize(it)) }
    val desired = friendTargets + queryTargets
    return MonitorSelection(
        selected = limit?.let(desired::take) ?: desired,
        allDesired = desired,
    )
}
