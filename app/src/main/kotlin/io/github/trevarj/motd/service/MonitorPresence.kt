package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.MonitorQueryRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.ext.MonitorSupport
import io.github.trevarj.motd.irc.ext.monitorSupport

internal data class MonitorSelection(
    val selected: List<String>,
    val allDesired: List<String>,
)

internal fun preferredMonitorNick(
    row: NetworkEntity?,
    ready: IrcClientState.Ready?,
    zncIds: Set<Long>?,
    normalize: (String) -> String,
): String? {
    val nick = ready?.preferredNickToRegain ?: return null
    if (row == null || !row.autoRegainPreferredNick || row.role != NetworkRole.DIRECT ||
        zncIds == null || row.id in zncIds || "BOUNCER_NETID" in ready.isupport ||
        normalize(nick) != normalize(row.nick)
    ) {
        return null
    }
    return when (monitorSupport(ready.isupport)) {
        MonitorSupport.Unlimited, is MonitorSupport.Limited -> nick
        else -> null
    }
}

internal fun selectMonitorTargets(
    preferredNick: String?,
    friends: Set<String>,
    queryRows: List<MonitorQueryRow>,
    limit: Int?,
    normalize: (String) -> String,
): MonitorSelection {
    val seen = HashSet<String>()
    val desired =
        buildList {
            fun addNick(nick: String) {
                if (seen.add(normalize(nick))) add(nick)
            }
            preferredNick?.let(::addNick)
            friends.sortedBy(normalize).forEach(::addNick)
            queryRows
                .sortedWith(
                    compareByDescending<MonitorQueryRow> { it.pinned }
                        .thenByDescending { it.lastMessageTime ?: Long.MIN_VALUE }
                        .thenBy { normalize(it.displayName) },
                ).forEach { addNick(it.displayName) }
        }
    return MonitorSelection(
        selected = limit?.let(desired::take) ?: desired,
        allDesired = desired,
    )
}
