package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage

/** Builds soju `BOUNCER` command lines (plans/03 soju.im/bouncer-networks). */
internal object BouncerCommands {
    fun listNetworks(): IrcMessage =
        IrcMessage(command = "BOUNCER", params = listOf("LISTNETWORKS"))

    fun addNetwork(attrs: Map<String, String>): IrcMessage =
        IrcMessage(command = "BOUNCER", params = listOf("ADDNETWORK", renderAttrString(attrs)))

    fun deleteNetwork(netId: String): IrcMessage =
        IrcMessage(command = "BOUNCER", params = listOf("DELNETWORK", netId))

    /** BOUNCER BIND <netid> — sent during registration before CAP END. */
    fun bind(netId: String): IrcMessage =
        IrcMessage(command = "BOUNCER", params = listOf("BIND", netId))

    /**
     * Parse a `BOUNCER NETWORK <netid> <attrs>` line from a LISTNETWORKS batch into
     * (netId, attrs). Returns null for anything else.
     */
    fun parseNetworkLine(msg: IrcMessage): Pair<String, Map<String, String>>? {
        if (msg.command != "BOUNCER" || msg.params.getOrNull(0) != "NETWORK") return null
        val netId = msg.params.getOrNull(1) ?: return null
        val attrs = parseAttrString(msg.params.getOrNull(2).orEmpty())
        return netId to attrs
    }

    /** Parse a `BOUNCER ADDNETWORK <netid>` reply into the assigned netId. */
    fun parseAddReply(msg: IrcMessage): String? {
        if (msg.command != "BOUNCER" || msg.params.getOrNull(0) != "ADDNETWORK") return null
        return msg.params.getOrNull(1)
    }
}
