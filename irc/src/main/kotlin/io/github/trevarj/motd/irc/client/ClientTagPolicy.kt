package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent

/**
 * Whether a client-only message tag may be sent under IRCv3 `CLIENTTAGDENY`.
 *
 * Tag names are accepted with or without the wire-only leading `+`. A missing token allows all
 * client tags. `*` denies all tags except explicit `-tag` exemptions; without `*`, listed tags are
 * denied and negated entries remain allowed.
 */
fun clientTagAllowed(
    isupport: Map<String, String>,
    tag: String,
): Boolean {
    val denied = isupport["CLIENTTAGDENY"] ?: return true
    val name = tag.removePrefix("+")
    val entries = denied.split(',').map(String::trim).filter(String::isNotEmpty)
    if (entries.any { it == "-$name" }) return true
    if (entries.any { it == "*" }) return false
    return entries.none { it == name }
}

/** A client tag also requires the negotiated `message-tags` capability. */
fun canSendClientTag(
    caps: Set<String>,
    isupport: Map<String, String>,
    tag: String,
): Boolean = caps.any { it.substringBefore('=') == "message-tags" } && clientTagAllowed(isupport, tag)

/** Valid private-message channel context; accepts the deployed draft alias on ingress. */
fun validChannelContext(
    event: IrcEvent.ChatMessage,
    isChannel: (String) -> Boolean,
    isMembershipPrefix: (Char) -> Boolean = { false },
): String? {
    val tags = event.ctx.clientTags
    val context = tags["+channel-context"] ?: tags["+draft/channel-context"] ?: return null
    if (context.length < 2 || !isChannel(context) || context.any { it == ',' || it.isWhitespace() || it.isISOControl() }) {
        return null
    }

    fun isPrivateNick(nick: String): Boolean =
        nick.isNotEmpty() && !isChannel(nick) &&
            nick.first() !in "$:" && !isMembershipPrefix(nick.first()) &&
            nick.none { it in ".,*!?@" || it.isWhitespace() || it.isISOControl() }

    return context.takeIf { isPrivateNick(event.target) && isPrivateNick(event.source.nick) }
}

fun preferredReplyTag(
    caps: Set<String>,
    isupport: Map<String, String>,
): String? =
    when {
        canSendClientTag(caps, isupport, "+reply") -> "+reply"
        canSendClientTag(caps, isupport, "+draft/reply") -> "+draft/reply"
        else -> null
    }

fun canSendReactionTags(
    caps: Set<String>,
    isupport: Map<String, String>,
    remove: Boolean,
): Boolean =
    preferredReplyTag(caps, isupport) != null &&
        canSendClientTag(caps, isupport, if (remove) "+draft/unreact" else "+draft/react")
