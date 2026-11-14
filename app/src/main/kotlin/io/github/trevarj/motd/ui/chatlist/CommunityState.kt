package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState

data class CommunityRoute(val network: NetworkEntity, val identified: Boolean, val ready: Boolean, val joinedBufferId: Long? = null)
enum class CommunityError { CONNECTION, TIMEOUT, REJECTED, FAILED }
data class CommunityState(
    val invitation: Boolean = false,
    val picker: Boolean = false,
    val routes: List<CommunityRoute> = emptyList(),
    val joining: Boolean = false,
    val error: CommunityError? = null,
    val rejectionReason: String? = null,
    val openBufferId: Long? = null,
)

fun isCommunityNetwork(network: NetworkEntity, connection: IrcClientState?): Boolean =
    network.role != NetworkRole.BOUNCER_ROOT &&
        ((connection as? IrcClientState.Ready)?.isupport?.get("NETWORK")?.equals("Libera.Chat", ignoreCase = true) == true ||
            (network.role == NetworkRole.DIRECT && network.wsUrl == null && network.tls && network.host.equals("irc.libera.chat", ignoreCase = true) && network.port == 6697))
