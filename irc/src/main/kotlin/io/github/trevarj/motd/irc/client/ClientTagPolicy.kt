package io.github.trevarj.motd.irc.client

/**
 * Whether a client-only message tag may be sent under IRCv3 `CLIENTTAGDENY`.
 *
 * Tag names are accepted with or without the wire-only leading `+`. A missing token allows all
 * client tags. `*` denies all tags except explicit `-tag` exemptions; without `*`, listed tags are
 * denied and negated entries remain allowed.
 */
fun clientTagAllowed(isupport: Map<String, String>, tag: String): Boolean {
    val denied = isupport["CLIENTTAGDENY"] ?: return true
    val name = tag.removePrefix("+")
    val entries = denied.split(',').map(String::trim).filter(String::isNotEmpty)
    if (entries.any { it == "-$name" }) return true
    if (entries.any { it == "*" }) return false
    return entries.none { it == name }
}

/** A client tag also requires the negotiated `message-tags` capability. */
fun canSendClientTag(caps: Set<String>, isupport: Map<String, String>, tag: String): Boolean =
    caps.any { it.substringBefore('=') == "message-tags" } && clientTagAllowed(isupport, tag)

fun canSendReactionTags(
    caps: Set<String>,
    isupport: Map<String, String>,
    remove: Boolean,
): Boolean = canSendClientTag(caps, isupport, "+reply") &&
    canSendClientTag(caps, isupport, if (remove) "+draft/unreact" else "+draft/react")
