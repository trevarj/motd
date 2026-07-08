package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage

/**
 * Builds `CHATHISTORY` request lines (plans/03). Pure string construction; the client sends the
 * result via `sendLabeled` and reassembles the response batch.
 *
 * Subcommands:
 *  - LATEST  <target> * <limit>
 *  - BEFORE  <target> <bound> <limit>
 *  - AFTER   <target> <bound> <limit>
 *  - AROUND  <target> <bound> <limit>
 *  - BETWEEN <target> <bound1> <bound2> <limit>
 *  - TARGETS <bound1> <bound2> <limit>
 *
 * Bounds are pre-rendered selectors like `timestamp=<ISO>` or `msgid=<id>`.
 */
internal object ChatHistoryCommands {
    fun latest(target: String, limit: Int): IrcMessage =
        IrcMessage(command = "CHATHISTORY", params = listOf("LATEST", target, "*", limit.toString()))

    fun before(target: String, bound: String, limit: Int): IrcMessage =
        IrcMessage(command = "CHATHISTORY", params = listOf("BEFORE", target, bound, limit.toString()))

    fun after(target: String, bound: String, limit: Int): IrcMessage =
        IrcMessage(command = "CHATHISTORY", params = listOf("AFTER", target, bound, limit.toString()))

    fun around(target: String, bound: String, limit: Int): IrcMessage =
        IrcMessage(command = "CHATHISTORY", params = listOf("AROUND", target, bound, limit.toString()))

    fun between(target: String, bound1: String, bound2: String, limit: Int): IrcMessage =
        IrcMessage(command = "CHATHISTORY", params = listOf("BETWEEN", target, bound1, bound2, limit.toString()))

    fun targets(bound1: String, bound2: String, limit: Int): IrcMessage =
        IrcMessage(command = "CHATHISTORY", params = listOf("TARGETS", bound1, bound2, limit.toString()))
}
