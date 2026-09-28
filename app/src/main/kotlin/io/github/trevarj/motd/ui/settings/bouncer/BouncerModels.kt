package io.github.trevarj.motd.ui.settings.bouncer

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.client.BouncerNetwork

/** One row: a network known to the bouncer, merged with its local mirror (if imported). */
data class BouncerNetRow(
    val netId: String,
    val name: String, // attrs["name"] ?: attrs["host"] ?: netId
    val host: String?, // attrs["host"]
    val bouncerState: String?, // attrs["state"]: "connected"/"connecting"/"disconnected"
    val childNetworkId: Long?, // local BOUNCER_CHILD row id; null = not imported
)

/**
 * Pure merge of the live bouncer listing with local child rows. A local child is
 * matched to a listing entry by [NetworkEntity.bouncerNetId]; the match sets [childNetworkId] so
 * the UI can show the "shown in motd" import toggle.
 */
fun mergeBouncerRows(
    listing: List<BouncerNetwork>,
    children: List<NetworkEntity>,
): List<BouncerNetRow> =
    listing.map { bn ->
        val child = children.firstOrNull { it.bouncerNetId == bn.netId }
        BouncerNetRow(
            netId = bn.netId,
            name = bn.attrs["name"] ?: bn.attrs["host"] ?: bn.netId,
            host = bn.attrs["host"],
            bouncerState = bn.attrs["state"],
            childNetworkId = child?.id,
        )
    }

/** Build local mirrors for listed bouncer networks that have not been imported yet. */
fun missingBouncerChildren(
    root: NetworkEntity,
    rows: List<BouncerNetRow>,
    children: List<NetworkEntity>,
): List<NetworkEntity> {
    val importedNetIds = children.mapTo(mutableSetOf()) { it.bouncerNetId }
    return rows.filter { it.netId !in importedNetIds }.map { row ->
        root.copy(
            id = 0,
            name = row.name,
            role = NetworkRole.BOUNCER_CHILD,
            parentId = root.id,
            bouncerNetId = row.netId,
            host = row.host ?: root.host,
            onConnectCommands = "",
        )
    }
}
