package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole

internal fun matchingIrcLinkAccounts(
    link: IrcChannelLink,
    networks: List<NetworkEntity>,
    zncIds: Set<Long>,
    cachedUpstream: (rootId: Long, netId: String) -> Map<String, String>?,
): List<NetworkEntity> {
    fun matches(
        host: String,
        port: Int,
        tls: Boolean,
    ): Boolean = port == link.port && tls == link.tls && canonicalIrcLinkHost(host) == link.host

    return networks.filter { network ->
        when (network.role) {
            NetworkRole.BOUNCER_ROOT -> {
                false
            }

            NetworkRole.DIRECT -> {
                network.id !in zncIds && network.saslUser?.contains('/') != true &&
                    network.serverPassword?.any { it == '/' || it == ':' } != true && matches(network.host, network.port, network.tls)
            }

            NetworkRole.BOUNCER_CHILD -> {
                val root = networks.firstOrNull { it.id == network.parentId && it.role == NetworkRole.BOUNCER_ROOT } ?: return@filter false
                if (matches(root.host, root.port, root.tls)) return@filter false
                val attrs = cachedUpstream(root.id, network.bouncerNetId ?: return@filter false) ?: return@filter false
                val host = attrs["host"] ?: return@filter false
                val tls =
                    when (attrs["tls"]) {
                        null, "1" -> true
                        "0" -> false
                        else -> return@filter false
                    }
                val port = attrs["port"]?.let { it.toIntOrNull() ?: return@filter false } ?: defaultIrcLinkPort(tls)
                matches(host, port, tls)
            }
        }
    }
}
