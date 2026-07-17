package io.github.trevarj.motd.ui.channellist

import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState

/** Server-side user-count floor when the network advertises ELIST 'U'. */
const val DEFAULT_MIN_USERS = 50
const val POPULAR_CHANNEL_LIMIT = 100
const val CHANNEL_SEARCH_LIMIT = 2000

/**
 * Sort listings by user count descending, stable for ties (plans/16 §5.7).
 * Kotlin's [sortedByDescending] is a stable sort, so equal counts keep input order.
 */
fun sortListings(listings: List<ChannelListing>): List<ChannelListing> =
    listings.sortedByDescending { it.userCount }

/** Prefer the scoped client's live state, especially when the manager snapshot has not caught up. */
fun channelBrowserConnectionState(
    managerState: IrcClientState?,
    clientState: IrcClientState?,
): IrcClientState = when {
    clientState != null -> clientState
    managerState != null -> managerState
    else -> IrcClientState.Disconnected
}

enum class ChannelBrowserAvailability {
    INITIALIZING,
    ROOT_UNAVAILABLE,
    CONNECTING,
    READY,
    OFFLINE,
    FAILED,
}

fun channelBrowserAvailability(
    initialized: Boolean,
    isRoot: Boolean,
    connection: IrcClientState,
): ChannelBrowserAvailability = when {
    !initialized -> ChannelBrowserAvailability.INITIALIZING
    isRoot -> ChannelBrowserAvailability.ROOT_UNAVAILABLE
    connection is IrcClientState.Ready -> ChannelBrowserAvailability.READY
    connection is IrcClientState.Connecting || connection is IrcClientState.Registering ->
        ChannelBrowserAvailability.CONNECTING
    connection is IrcClientState.Failed -> ChannelBrowserAvailability.FAILED
    else -> ChannelBrowserAvailability.OFFLINE
}

/**
 * Resolve the LIST arguments for a fetch (plans/16 §5.7).
 *
 * A non-blank [query] fetches with a `*query*` substring mask and no min-users floor. A blank
 * query auto-fetches the busiest channels ([DEFAULT_MIN_USERS] floor, applied server-side only
 * when ELIST 'U' is present — the `:irc` layer gates the `>n` param itself).
 */
data class ListArgs(val mask: String?, val minUsers: Int?)

fun listArgsFor(query: String): ListArgs =
    if (query.isBlank()) {
        ListArgs(mask = null, minUsers = DEFAULT_MIN_USERS)
    } else {
        ListArgs(mask = "*${query.trim()}*", minUsers = null)
    }

/** Popular browsing is deliberately compact; explicit searches may return a larger result set. */
fun channelListLimit(query: String): Int =
    if (query.isBlank()) POPULAR_CHANNEL_LIMIT else CHANNEL_SEARCH_LIMIT
