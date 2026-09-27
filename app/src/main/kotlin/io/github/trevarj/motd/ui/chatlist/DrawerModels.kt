package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState

/**
 * Pure models for the server drawer. Unit-tested against the same [ChatListRow]
 * fixtures as sectioning; no Compose/Android imports so the rollup math stays verifiable.
 */

/** One drawer entry. depth = 0 for DIRECT/BOUNCER_ROOT, 1 for BOUNCER_CHILD. */
data class DrawerRow(
    val networkId: Long,
    val name: String,
    val role: NetworkRole,
    val depth: Int,
    val state: IrcClientState, // IrcClientState.Disconnected when absent from the map
    val nick: String?, // (state as? Ready)?.nick
    val unread: Int, // sum of unreadCount over the network's non-muted rows
    val mentions: Int, // sum of mentionCount over the network's non-muted rows
    val iconUrl: String? = null,
    val unreadIncomplete: Boolean = false,
    val mentionsIncomplete: Boolean = false,
    val isZnc: Boolean = false,
)

/** Rollup of unread + mention counts for a single set of non-muted rows. */
private data class Rollup(
    val unread: Int,
    val mentions: Int,
    val unreadIncomplete: Boolean = false,
    val mentionsIncomplete: Boolean = false,
)

private fun rollupFor(
    rows: List<ChatListRow>,
    networkId: Long,
): Rollup {
    var unread = 0
    var mentions = 0
    var unreadIncomplete = false
    var mentionsIncomplete = false
    for (row in rows) {
        if (row.networkId != networkId) continue
        if (row.muted) continue
        unread += row.unreadCount
        mentions += row.mentionCount
        unreadIncomplete = unreadIncomplete || row.unreadCountIncomplete
        mentionsIncomplete = mentionsIncomplete || row.mentionCountIncomplete
    }
    return Rollup(unread, mentions, unreadIncomplete, mentionsIncomplete)
}

private fun stateFor(
    states: Map<Long, IrcClientState>,
    id: Long,
): IrcClientState = states[id] ?: IrcClientState.Disconnected

/**
 * Build drawer rows in DB order (`networks` already ordered), each BOUNCER_ROOT immediately
 * followed by its children. A root aggregates its children's counts into its own row so a
 * collapsed root still surfaces child activity.
 */
fun buildDrawerRows(
    networks: List<NetworkEntity>,
    rows: List<ChatListRow>,
    states: Map<Long, IrcClientState>,
    zncNetworkIds: Set<Long> = emptySet(),
): List<DrawerRow> {
    val childrenByParent =
        networks
            .filter { it.role == NetworkRole.BOUNCER_CHILD && it.parentId != null }
            .groupBy { it.parentId!! }

    fun rowFor(
        net: NetworkEntity,
        depth: Int,
        extra: Rollup = Rollup(0, 0),
    ): DrawerRow {
        val state = stateFor(states, net.id)
        val own = rollupFor(rows, net.id)
        return DrawerRow(
            networkId = net.id,
            name = net.name,
            role = net.role,
            depth = depth,
            state = state,
            nick = (state as? IrcClientState.Ready)?.nick,
            iconUrl = net.serverIconUrl,
            isZnc = net.role == NetworkRole.DIRECT && net.id in zncNetworkIds,
            unread = own.unread + extra.unread,
            mentions = own.mentions + extra.mentions,
            unreadIncomplete = own.unreadIncomplete || extra.unreadIncomplete,
            mentionsIncomplete = own.mentionsIncomplete || extra.mentionsIncomplete,
        )
    }

    val out = ArrayList<DrawerRow>()
    for (net in networks) {
        when (net.role) {
            NetworkRole.BOUNCER_CHILD -> {}

            // emitted under its parent below
            NetworkRole.BOUNCER_ROOT -> {
                val kids = childrenByParent[net.id].orEmpty()
                // Aggregate children's counts into the root's own row.
                val childTotals =
                    kids.fold(Rollup(0, 0)) { acc, kid ->
                        val r = rollupFor(rows, kid.id)
                        Rollup(
                            acc.unread + r.unread,
                            acc.mentions + r.mentions,
                            acc.unreadIncomplete || r.unreadIncomplete,
                            acc.mentionsIncomplete || r.mentionsIncomplete,
                        )
                    }
                out.add(rowFor(net, depth = 0, extra = childTotals))
                for (kid in kids) out.add(rowFor(kid, depth = 1))
            }

            NetworkRole.DIRECT -> {
                out.add(rowFor(net, depth = 0))
            }
        }
    }
    return out
}

/**
 * The network ids a selection covers: null when unscoped (everything); a
 * DIRECT/BOUNCER_CHILD id covers only itself; a BOUNCER_ROOT id covers the root plus all its
 * children. Shared by [scopeRows] and the title connectivity cue so "what the scoped list shows"
 * and "which sockets the scoped title reports on" can never disagree.
 */
fun scopeNetworkIds(
    selectedNetworkId: Long?,
    networks: List<NetworkEntity>,
): Set<Long>? {
    if (selectedNetworkId == null) return null
    val selected = networks.firstOrNull { it.id == selectedNetworkId }
    return when (selected?.role) {
        NetworkRole.BOUNCER_ROOT -> {
            networks.filter { it.parentId == selectedNetworkId }.map { it.id }.toSet() + selectedNetworkId
        }

        else -> {
            setOf(selectedNetworkId)
        }
    }
}

/**
 * Filter the unified chat list by the selected network:
 * null = all rows (identity); a DIRECT/BOUNCER_CHILD id filters to that network; a BOUNCER_ROOT
 * id includes the root plus all its children's rows.
 */
fun scopeRows(
    rows: List<ChatListRow>,
    selectedNetworkId: Long?,
    networks: List<NetworkEntity>,
): List<ChatListRow> {
    val scopeIds = scopeNetworkIds(selectedNetworkId, networks) ?: return rows
    return rows.filter { it.networkId in scopeIds }
}
