package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.irc.event.IrcEvent

/** Pure provenance policy applied before an IRC event reaches persistence handlers. */
internal enum class EventOrigin(
    val notifies: Boolean,
    val mutatesSessionState: Boolean,
) {
    LIVE(notifies = true, mutatesSessionState = true),
    HISTORY(notifies = false, mutatesSessionState = false),
    PUSH(notifies = true, mutatesSessionState = false),
    ;

    /** Push delivery has a deliberately narrow persistence surface. */
    fun accepts(event: IrcEvent): Boolean = this != PUSH || when (event) {
        is IrcEvent.ChatMessage,
        is IrcEvent.TagMessage,
        is IrcEvent.Invited,
        is IrcEvent.Raw,
        -> true
        else -> false
    }
}
