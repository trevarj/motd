package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.irc.proto.IrcMessage

/**
 * Parsed WHOIS details for the nick sheet (plans/16 §5.8). Every field is optional because a server
 * may omit any numeric; the sheet renders only the lines it has.
 */
data class WhoisInfo(
    val nick: String,
    val username: String? = null,
    val host: String? = null,
    val realname: String? = null,
    val server: String? = null,
    val serverInfo: String? = null,
    val account: String? = null,
    val channels: List<String> = emptyList(),
    val idleSecs: Long? = null,
    val signonEpochSecs: Long? = null,
    val awayMessage: String? = null,
)

/** Nick-sheet state: the target nick plus its WHOIS details once they land (plans/16 §5.8). */
data class NickSheetState(val nick: String, val whois: WhoisInfo? = null)

/**
 * Fold WHOIS numerics from a labeled response into a [WhoisInfo]. Recognized numerics:
 *
 * - `311` RPL_WHOISUSER: params = [me, nick, user, host, "*", realname]
 * - `312` RPL_WHOISSERVER: params = [me, nick, server, serverInfo]
 * - `301` RPL_AWAY: params = [me, nick, awayMessage]
 * - `317` RPL_WHOISIDLE: params = [me, nick, idleSecs, (signon), ...]
 * - `319` RPL_WHOISCHANNELS: params = [me, nick, channelList]
 * - `330` RPL_WHOISACCOUNT: params = [me, nick, account, "is logged in as"]
 *
 * Returns null when neither a `311` nor a `318` (end-of-WHOIS) is present, i.e. the response does
 * not describe a real WHOIS (plans/16 §5.8 acceptance).
 */
fun parseWhois(lines: List<IrcMessage>): WhoisInfo? {
    val has311 = lines.any { it.command == "311" }
    val has318 = lines.any { it.command == "318" }
    if (!has311 && !has318) return null

    // Nick comes from the 311/318 second param; fall back to any WHOIS numeric's nick param.
    val nick = lines.firstNotNullOfOrNull { it.params.getOrNull(1)?.takeIf { n -> n.isNotEmpty() } }
        ?: return null

    var info = WhoisInfo(nick = nick)
    for (msg in lines) {
        val p = msg.params
        when (msg.command) {
            "311" -> info = info.copy(
                username = p.getOrNull(2),
                host = p.getOrNull(3),
                realname = p.getOrNull(5),
            )
            "312" -> info = info.copy(server = p.getOrNull(2), serverInfo = p.getOrNull(3))
            "301" -> info = info.copy(awayMessage = p.getOrNull(2))
            "317" -> info = info.copy(
                idleSecs = p.getOrNull(2)?.toLongOrNull(),
                signonEpochSecs = p.getOrNull(3)?.toLongOrNull(),
            )
            "319" -> info = info.copy(
                channels = p.getOrNull(2)?.trim()?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            )
            "330" -> info = info.copy(account = p.getOrNull(2))
        }
    }
    return info
}
