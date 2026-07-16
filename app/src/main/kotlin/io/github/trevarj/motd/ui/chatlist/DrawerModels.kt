package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState

/**
 * Pure models for the server drawer (plans/16 §3.3). Unit-tested against the same [ChatListRow]
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
)

/** Rollup of unread + mention counts for a single set of non-muted rows. */
private data class Rollup(val unread: Int, val mentions: Int)

private fun rollupFor(rows: List<ChatListRow>, networkId: Long): Rollup {
    var unread = 0
    var mentions = 0
    for (row in rows) {
        if (row.networkId != networkId) continue
        if (row.muted) continue
        unread += row.unreadCount
        mentions += row.mentionCount
    }
    return Rollup(unread, mentions)
}

private fun stateFor(states: Map<Long, IrcClientState>, id: Long): IrcClientState =
    states[id] ?: IrcClientState.Disconnected

/**
 * Build drawer rows in DB order (`networks` already ordered), each BOUNCER_ROOT immediately
 * followed by its children. A root aggregates its children's counts into its own row so a
 * collapsed root still surfaces child activity (plans/16 §3.2).
 */
fun buildDrawerRows(
    networks: List<NetworkEntity>,
    rows: List<ChatListRow>,
    states: Map<Long, IrcClientState>,
): List<DrawerRow> {
    val childrenByParent = networks
        .filter { it.role == NetworkRole.BOUNCER_CHILD && it.parentId != null }
        .groupBy { it.parentId!! }

    fun rowFor(net: NetworkEntity, depth: Int, extra: Rollup = Rollup(0, 0)): DrawerRow {
        val state = stateFor(states, net.id)
        val own = rollupFor(rows, net.id)
        return DrawerRow(
            networkId = net.id,
            name = net.name,
            role = net.role,
            depth = depth,
            state = state,
            nick = (state as? IrcClientState.Ready)?.nick,
            unread = own.unread + extra.unread,
            mentions = own.mentions + extra.mentions,
        )
    }

    val out = ArrayList<DrawerRow>()
    for (net in networks) {
        when (net.role) {
            NetworkRole.BOUNCER_CHILD -> Unit // emitted under its parent below
            NetworkRole.BOUNCER_ROOT -> {
                val kids = childrenByParent[net.id].orEmpty()
                // Aggregate children's counts into the root's own row.
                val childTotals = kids.fold(Rollup(0, 0)) { acc, kid ->
                    val r = rollupFor(rows, kid.id)
                    Rollup(acc.unread + r.unread, acc.mentions + r.mentions)
                }
                out.add(rowFor(net, depth = 0, extra = childTotals))
                for (kid in kids) out.add(rowFor(kid, depth = 1))
            }
            NetworkRole.DIRECT -> out.add(rowFor(net, depth = 0))
        }
    }
    return out
}

/**
 * Filter the unified chat list by the selected network (plans/16 §3.3):
 * null = all rows (identity); a DIRECT/BOUNCER_CHILD id filters to that network; a BOUNCER_ROOT
 * id includes the root plus all its children's rows.
 */
fun scopeRows(
    rows: List<ChatListRow>,
    selectedNetworkId: Long?,
    networks: List<NetworkEntity>,
): List<ChatListRow> {
    if (selectedNetworkId == null) return rows
    val selected = networks.firstOrNull { it.id == selectedNetworkId }
    val scopeIds = when (selected?.role) {
        NetworkRole.BOUNCER_ROOT ->
            networks.filter { it.parentId == selectedNetworkId }.map { it.id }.toSet() + selectedNetworkId
        else -> setOf(selectedNetworkId)
    }
    return rows.filter { it.networkId in scopeIds }
}
