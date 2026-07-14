package io.github.trevarj.motd.irc.proto

/**
 * Return the message ID referenced by an IRC reply tag.
 *
 * `+reply` is the standardized client-only tag and wins when multiple aliases are present.
 * Older clients and servers still use the draft spelling; the unprefixed forms are accepted for
 * compatibility with history bridges which promote client-only metadata to server tags.
 */
fun IrcMessage.replyReference(): String? =
    tags["+reply"]
        ?: tags["+draft/reply"]
        ?: tags["reply"]
        ?: tags["draft/reply"]

/** Reaction value across the current draft spelling and deployed compatibility aliases. */
fun IrcMessage.reactionValue(): String? =
    tags["+draft/react"]
        ?: tags["+react"]
        ?: tags["draft/react"]
        ?: tags["react"]

/** Reaction-removal value across the current draft spelling and deployed compatibility aliases. */
fun IrcMessage.unreactionValue(): String? =
    tags["+draft/unreact"]
        ?: tags["+unreact"]
        ?: tags["draft/unreact"]
        ?: tags["unreact"]
