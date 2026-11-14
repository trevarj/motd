package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole

private const val LIBERA_HOST = "irc.libera.chat"
private const val LIBERA_TLS_PORT = 6697
private const val OFTC_HOST = "irc.oftc.net"
private const val OFTC_TLS_PORT = 6697

internal fun NetworkEntity?.isDirectLiberaEndpoint(): Boolean =
    this?.role == NetworkRole.DIRECT &&
        host.trim().trimEnd('.').equals(LIBERA_HOST, ignoreCase = true) &&
        port == LIBERA_TLS_PORT && tls

internal fun NetworkEntity?.isDirectOftcEndpoint(): Boolean =
    this?.role == NetworkRole.DIRECT &&
        host.trim().trimEnd('.').equals(OFTC_HOST, ignoreCase = true) &&
        port == OFTC_TLS_PORT && tls
