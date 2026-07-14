package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage

internal object WhoxCommands {
    const val FIELDS = "tuhnafr"

    fun request(mask: String, token: Int): IrcMessage {
        require(token in 0..999) { "WHOX token must be 0..999" }
        return IrcMessage(command = "WHO", params = listOf(mask, "%$FIELDS,$token"))
    }
}
