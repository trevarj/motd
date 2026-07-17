package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/**
 * Renders an IRCv3 CHATHISTORY timestamp selector with the millisecond precision required by
 * soju. [Instant.toString] drops a zero fractional part, which soju rejects for these bounds.
 */
object ChatHistorySelectors {
    private val timestampFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    fun timestamp(epochMillis: Long): String =
        "timestamp=${timestampFormatter.format(Instant.ofEpochMilli(epochMillis))}"

    /** IRCv3 message references are opaque and case-sensitive. */
    fun msgid(value: String): String = "msgid=$value"
}

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
