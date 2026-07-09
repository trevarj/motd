package io.github.trevarj.motd.ui.settings.bouncer

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.irc.client.BouncerNetwork

/** One row: a network known to the bouncer, merged with its local mirror (if imported). */
data class BouncerNetRow(
    val netId: String,
    val name: String,             // attrs["name"] ?: attrs["host"] ?: netId
    val host: String?,            // attrs["host"]
    val bouncerState: String?,    // attrs["state"]: "connected"/"connecting"/"disconnected"
    val childNetworkId: Long?,    // local BOUNCER_CHILD row id; null = not imported
)

/**
 * Pure merge of the live bouncer listing with local child rows (plans/16 §5.5). A local child is
 * matched to a listing entry by [NetworkEntity.bouncerNetId]; the match sets [childNetworkId] so
 * the UI can show the "shown in MOTD" import toggle.
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
