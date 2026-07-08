package io.github.trevarj.motd.ui.chat

/**
 * Pure translation of a composer line into a [ChatCommand]. No side effects, no Android/IRC deps,
 * so it is trivially unit-testable (WP7 acceptance). The ViewModel executes the returned command
 * against [io.github.trevarj.motd.service.ConnectionManager] / the network's client (plans/07).
 *
 * Lines that do not start with `/` are ordinary messages. A leading `//` escapes to a literal `/`
 * message. `/me` maps to a raw `/me` PRIVMSG (the manager translates it to an ACTION, plans/05).
 * Unknown `/cmd` becomes [ChatCommand.RawLine] with the leading slash stripped, sent verbatim via
 * `IrcMessage.parse` (plans/07).
 */
sealed interface ChatCommand {
    /** Ordinary PRIVMSG (or `/me` action, which the manager rewrites). */
    data class Message(val text: String) : ChatCommand

    /** `/join #chan` */
    data class Join(val channel: String) : ChatCommand

    /** `/part [reason]` — parts the current buffer. */
    data class Part(val reason: String?) : ChatCommand

    /** `/msg nick text` — DM with an immediate message. */
    data class Msg(val nick: String, val text: String) : ChatCommand

    /** `/query nick` — open a DM buffer, no message. */
    data class Query(val nick: String) : ChatCommand

    /** `/nick newnick` — raw NICK on the current network's client. */
    data class Nick(val nick: String) : ChatCommand

    /** `/topic text` — set the current channel's topic. */
    data class Topic(val topic: String) : ChatCommand

    /** Unknown `/cmd args` — send as a raw line (slash stripped) via `IrcMessage.parse`. */
    data class RawLine(val line: String) : ChatCommand

    /** Blank input / bare `/` — nothing to do. */
    data object None : ChatCommand
}

/** The slash-commands offered in the composer hint popup, in display order. */
val COMMAND_HINTS: List<String> = listOf("/me", "/join", "/part", "/msg", "/query", "/nick", "/topic")

/**
 * Parse [raw] composer input into a [ChatCommand]. See the type doc for the rules. This is the
 * single source of truth for slash-command behavior and the target of the WP7 parser unit test.
 */
fun parseCommand(raw: String): ChatCommand {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ChatCommand.None

    // Not a command — ordinary message. `//text` escapes a literal leading slash.
    if (!trimmed.startsWith("/")) return ChatCommand.Message(trimmed)
    if (trimmed.startsWith("//")) return ChatCommand.Message(trimmed.substring(1))

    // Split "/cmd" from the remainder (single space, remainder kept raw for text args).
    val afterSlash = trimmed.substring(1)
    val space = afterSlash.indexOf(' ')
    val cmd = (if (space < 0) afterSlash else afterSlash.substring(0, space)).lowercase()
    val rest = if (space < 0) "" else afterSlash.substring(space + 1).trim()

    // Bare "/" — nothing to send.
    if (cmd.isEmpty()) return ChatCommand.None

    return when (cmd) {
        "me" -> if (rest.isEmpty()) ChatCommand.None else ChatCommand.Message("/me $rest")
        "join" -> {
            val channel = rest.substringBefore(' ').trim()
            if (channel.isEmpty()) ChatCommand.None else ChatCommand.Join(channel)
        }
        "part" -> ChatCommand.Part(rest.ifEmpty { null })
        "msg" -> {
            val nick = rest.substringBefore(' ').trim()
            val text = rest.substringAfter(' ', "").trim()
            if (nick.isEmpty() || text.isEmpty()) ChatCommand.None else ChatCommand.Msg(nick, text)
        }
        "query" -> {
            val nick = rest.substringBefore(' ').trim()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Query(nick)
        }
        "nick" -> {
            val nick = rest.substringBefore(' ').trim()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Nick(nick)
        }
        "topic" -> if (rest.isEmpty()) ChatCommand.None else ChatCommand.Topic(rest)
        // Unknown command: pass through raw, slash stripped (e.g. "whois nick").
        else -> ChatCommand.RawLine(afterSlash)
    }
}
