package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.channellist.exactChannelTarget
import java.net.IDN
import java.net.URI

internal data class IrcChannelLink(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val channel: String,
)

private val ircAuthority = Regex("(\\[[0-9a-fA-F:.]+]|[^:/@%\\[\\]]+)(?::([0-9]+))?")
private val linkIdentityRules = IrcIdentityRules()

internal fun parseIrcChannelLink(raw: String): IrcChannelLink? {
    if (raw.any(::invalidLinkCharacter)) return null
    val fragmentIndex = raw.indexOf('#')
    val uriText =
        if (fragmentIndex > 0 && raw[fragmentIndex - 1] == '/' && raw.getOrNull(fragmentIndex + 1) == '#') {
            val extraHashes = raw.substring(fragmentIndex + 1).takeWhile { it == '#' }
            raw.substring(0, fragmentIndex + 1) + "%23".repeat(extraHashes.length) + raw.substring(fragmentIndex + 1 + extraHashes.length)
        } else {
            raw
        }
    val uri = runCatching { URI(uriText) }.getOrNull() ?: return null
    val tls =
        when (uri.scheme?.lowercase()) {
            "irc" -> false
            "ircs" -> true
            else -> return null
        }
    if (uri.rawQuery != null || uri.rawUserInfo != null) return null
    val authority = ircAuthority.matchEntire(uri.rawAuthority ?: return null) ?: return null
    val host = canonicalIrcLinkHost(authority.groupValues[1]) ?: return null
    val port = authority.groups[2]?.value?.let { it.toIntOrNull() ?: return null } ?: defaultIrcLinkPort(tls)
    if (port !in 1..65535) return null

    val path = uri.path?.takeIf { it.startsWith('/') } ?: return null
    val target =
        if (uri.rawFragment != null) {
            if (path != "/") return null
            "#${uri.fragment}"
        } else {
            path.substring(1)
        }
    if (target.any { invalidLinkCharacter(it) || it in "/?\uFFFD" }) return null
    val channel =
        exactChannelTarget(if (linkIdentityRules.isChannel(target)) target else "#$target", linkIdentityRules)
            ?: return null
    return IrcChannelLink(host, port, tls, channel)
}

private fun invalidLinkCharacter(char: Char): Boolean = char.isWhitespace() || char.isISOControl() || char.category == CharCategory.FORMAT

internal fun defaultIrcLinkPort(tls: Boolean): Int = if (tls) 6697 else 6667

internal fun canonicalIrcLinkHost(raw: String): String? {
    if (raw.any { invalidLinkCharacter(it) || it in "%/@?#" }) return null
    if (':' in raw) {
        val host = raw.removeSurrounding("[", "]")
        return runCatching { URI("irc://[$host]/").host }.getOrNull()?.let { host.lowercase() }
    }
    val ascii = runCatching { IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES) }.getOrNull()?.removeSuffix(".") ?: return null
    return ascii.lowercase().takeIf { it.length <= 253 && it.split('.').all { label -> label.length in 1..63 } }
}
