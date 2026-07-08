package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.time.Instant

/** Builds `MARKREAD` request lines (plans/03 draft/read-marker). */
internal object ReadMarkerCommands {
    /** MARKREAD <target> timestamp=<ISO> — set. */
    fun set(target: String, timestampMs: Long): IrcMessage {
        val iso = Instant.ofEpochMilli(timestampMs).toString()
        return IrcMessage(command = "MARKREAD", params = listOf(target, "timestamp=$iso"))
    }

    /** MARKREAD <target> — get (server echoes current marker to all clients). */
    fun get(target: String): IrcMessage =
        IrcMessage(command = "MARKREAD", params = listOf(target))
}
