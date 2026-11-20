package io.github.trevarj.motd.ui.channellist

import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.irc.proto.IrcIdentityRules

internal const val SMALL_CHANNEL_MAX_USERS = 50
internal const val DIRECTORY_CHANNEL_LIMIT = 100
internal const val CHANNEL_SEARCH_LIMIT = 2000

/** Prefer the scoped client's live state, especially when the manager snapshot has not caught up. */
fun channelBrowserConnectionState(
    managerState: IrcClientState?,
    clientState: IrcClientState?,
): IrcClientState =
    when {
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
): ChannelBrowserAvailability =
    when {
        !initialized -> {
            ChannelBrowserAvailability.INITIALIZING
        }

        isRoot -> {
            ChannelBrowserAvailability.ROOT_UNAVAILABLE
        }

        connection is IrcClientState.Ready -> {
            ChannelBrowserAvailability.READY
        }

        connection is IrcClientState.Connecting || connection is IrcClientState.Registering -> {
            ChannelBrowserAvailability.CONNECTING
        }

        connection is IrcClientState.Failed -> {
            ChannelBrowserAvailability.FAILED
        }

        else -> {
            ChannelBrowserAvailability.OFFLINE
        }
    }

internal fun filterLoadedChannels(
    listings: List<ChannelListing>,
    query: String,
    identityRules: IrcIdentityRules,
): List<ChannelListing> {
    val term = query.trim()
    if (term.isEmpty()) return listings
    val normalized = identityRules.normalize(term)
    return listings.filter { listing ->
        identityRules.normalize(listing.name).contains(normalized) ||
            listing.topic.let { topic ->
                val visible = if (topic.any(Char::isISOControl)) plainIrcText(topic) else topic
                visible.contains(term, ignoreCase = true)
            }
    }
}

internal fun networkSearchTerm(input: String): String? = input.trim().takeIf(String::isIrcParameter)

internal fun exactChannelTarget(
    input: String,
    identityRules: IrcIdentityRules,
): String? =
    input.trim().takeIf {
        it.length > 1 && identityRules.isChannel(it) && it.isIrcParameter() && ':' !in it
    }

internal fun String.isIrcParameter(): Boolean =
    isNotEmpty() && length <= 400 && none { it == ',' || it.isWhitespace() || it.isISOControl() } &&
        encodeToByteArray().size <= 400
