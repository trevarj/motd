package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.format.IrcEditorDocument
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.irc.format.plainIrcText

/**
 * Pure translation of a composer line into a [ChatCommand]. No side effects, no Android/IRC deps,
 * so it is trivially unit-testable (WP7 acceptance). The ViewModel executes the returned command
 * against [io.github.trevarj.motd.service.ConnectionManager] / the network's client.
 *
 * Lines that do not start with `/` are ordinary messages. A leading `//` escapes to a literal `/`
 * message. `/me` maps to a raw `/me` PRIVMSG (the manager translates it to an ACTION).
 * Unknown `/cmd` becomes [ChatCommand.RawLine] with the leading slash stripped, sent verbatim via
 * `IrcMessage.parse`.
 *
 * Commands that address a channel accept the target implicitly: run them in the channel's own
 * conversation and the ViewModel supplies the buffer's target, matching how `/topic` and `/kick`
 * already behave.
 */
sealed interface ChatCommand {
    /** Ordinary PRIVMSG (or `/me` action, which the manager rewrites). */
    data class Message(
        val text: String,
    ) : ChatCommand

    /**
     * `/join #chan`, `/join #chan key`, `/join #a,#b key-a,key-b`.
     *
     * [channels] and [keys] stay in their comma-joined wire form: JOIN takes exactly two parameters
     * and pairs them positionally, so splitting and rejoining them here would only risk losing that
     * pairing.
     */
    data class Join(
        val channels: String,
        val keys: String? = null,
    ) : ChatCommand

    /** `/part [reason]` — parts the current buffer. */
    data class Part(
        val reason: String?,
    ) : ChatCommand

    /** `/hop [reason]` — part the current channel and immediately rejoin it. */
    data class Hop(
        val reason: String?,
    ) : ChatCommand

    /** `/msg nick text` — DM with an immediate message. */
    data class Msg(
        val nick: String,
        val text: String,
    ) : ChatCommand

    /** `/query nick` — open a DM buffer, no message. */
    data class Query(
        val nick: String,
    ) : ChatCommand

    /** `/notice target text` — NOTICE, which by convention is not auto-replied to. */
    data class Notice(
        val target: String,
        val text: String,
    ) : ChatCommand

    /** `/nick newnick` — raw NICK on the current network's client. */
    data class Nick(
        val nick: String,
    ) : ChatCommand

    /** `/setname realname` — REALNAME/SETNAME for servers advertising `setname`. */
    data class SetName(
        val realname: String,
    ) : ChatCommand

    /** `/topic text` — set the current channel's topic. */
    data class Topic(
        val topic: String,
    ) : ChatCommand

    /**
     * `/mode [target] modes [args]` — null [target] means the current buffer's own target, and null
     * [modes] queries the modes currently set rather than changing any.
     */
    data class Mode(
        val target: String?,
        val modes: String?,
    ) : ChatCommand

    /** `/away [message]` — away with a message; `/back` or bare `/away` clears it. */
    data class Away(
        val message: String?,
    ) : ChatCommand

    /** `/whois nick` — open the nick sheet with WHOIS details. */
    data class Whois(
        val nick: String,
    ) : ChatCommand

    /** `/list` — open the channel browser for the current network. */
    data object ChannelList : ChatCommand

    /** `/kick nick [reason]` — kick from the current channel. */
    data class Kick(
        val nick: String,
        val reason: String?,
    ) : ChatCommand

    /** `/ban nick` — MODE +b nick!*@* on the current channel. */
    data class Ban(
        val nick: String,
    ) : ChatCommand

    /** `/op [nick]` or `/deop [nick]` in the current channel. An omitted nick means self. */
    data class ChannelOperator(
        val nick: String?,
        val grant: Boolean,
    ) : ChatCommand

    /** `/invite nick [channel]` — null channel invites to the current channel. */
    data class Invite(
        val nick: String,
        val channel: String?,
    ) : ChatCommand

    /** `/knock #chan [reason]` — request an invite to an invite-only channel. */
    data class Knock(
        val channel: String,
        val reason: String?,
    ) : ChatCommand

    /** `/ctcp nick REQUEST [args]` — a CTCP request such as `PING`, `VERSION`, or `TIME`. */
    data class Ctcp(
        val nick: String,
        val request: String,
    ) : ChatCommand

    /** `/motd [server]` — re-request the message of the day. */
    data class Motd(
        val server: String?,
    ) : ChatCommand

    /** Unknown `/cmd args`, or an explicit `/raw line` — sent verbatim via `IrcMessage.parse`. */
    data class RawLine(
        val line: String,
    ) : ChatCommand

    /** Blank input / bare `/` — nothing to do. */
    data object None : ChatCommand
}

/** Commands that only mean something inside a channel conversation. */
val CHANNEL_ONLY_COMMANDS: Set<String> =
    setOf("/topic", "/kick", "/ban", "/invite", "/hop", "/part", "/op", "/deop")

internal data class CommandParameter(
    val label: Int,
    val optional: Boolean = false,
)

private val nickParameter = CommandParameter(R.string.chat_parameter_nick)
private val channelParameter = CommandParameter(R.string.chat_parameter_channel)
private val messageParameter = CommandParameter(R.string.chat_parameter_message)
private val reasonParameter = CommandParameter(R.string.chat_parameter_reason, optional = true)
private val targetParameter = CommandParameter(R.string.chat_parameter_target)
private val argumentsParameter = CommandParameter(R.string.chat_parameter_arguments, optional = true)
private val commandParameters =
    mapOf(
        "/me" to listOf(CommandParameter(R.string.chat_parameter_action)),
        "/join" to listOf(CommandParameter(R.string.chat_parameter_channels), CommandParameter(R.string.chat_parameter_keys, optional = true)),
        "/part" to listOf(reasonParameter),
        "/hop" to listOf(reasonParameter),
        "/msg" to listOf(nickParameter, messageParameter),
        "/query" to listOf(nickParameter),
        "/notice" to listOf(targetParameter, messageParameter),
        "/nick" to listOf(CommandParameter(R.string.chat_parameter_new_nick)),
        "/setname" to listOf(CommandParameter(R.string.chat_parameter_real_name)),
        "/topic" to listOf(CommandParameter(R.string.chat_parameter_topic)),
        "/mode" to listOf(targetParameter.copy(optional = true), CommandParameter(R.string.chat_parameter_modes, optional = true), argumentsParameter),
        "/away" to listOf(messageParameter.copy(optional = true)),
        "/whois" to listOf(nickParameter),
        "/list" to emptyList(),
        "/kick" to listOf(nickParameter, reasonParameter),
        "/ban" to listOf(nickParameter),
        "/op" to listOf(nickParameter.copy(optional = true)),
        "/deop" to listOf(nickParameter.copy(optional = true)),
        "/invite" to listOf(nickParameter, channelParameter.copy(optional = true)),
        "/knock" to listOf(channelParameter, reasonParameter),
        "/ctcp" to listOf(nickParameter, CommandParameter(R.string.chat_parameter_request), argumentsParameter),
        "/motd" to listOf(CommandParameter(R.string.chat_parameter_server, optional = true)),
        "/raw" to listOf(CommandParameter(R.string.chat_parameter_irc_line)),
    )

private val commandAliases =
    mapOf(
        "describe" to "me",
        "j" to "join",
        "leave" to "part",
        "rejoin" to "hop",
        "t" to "topic",
        "m" to "mode",
        "quote" to "raw",
    )

/** Aliases stay accepted without doubling the length of the composer popup. */
val COMMAND_HINTS: List<String> = commandParameters.keys.toList()

/**
 * Hints appropriate for the conversation the composer is in. Channel-only commands are withheld
 * outside a channel rather than offered and then silently ignored.
 */
fun commandHintsFor(isChannel: Boolean): List<String> = if (isChannel) COMMAND_HINTS else COMMAND_HINTS.filterNot { it in CHANNEL_ONLY_COMMANDS }

internal fun remainingCommandParameters(
    text: String,
    isChannel: Boolean,
): List<CommandParameter> {
    if (!text.startsWith('/') || text.startsWith("//") || '\n' in text || '\r' in text) return emptyList()
    val command = text.substring(1).firstWord().lowercase()
    val canonical = "/${commandAliases[command] ?: command}"
    if (!isChannel && canonical in CHANNEL_ONLY_COMMANDS) return emptyList()
    val parameters = commandParameters[canonical] ?: return emptyList()
    var rest = text.afterFirstWord()
    // A leading sign is the mode string, not an explicit target.
    var supplied = if (canonical == "/mode" && (rest.startsWith('+') || rest.startsWith('-'))) 1 else 0
    while (supplied < parameters.size && rest.isNotEmpty()) {
        supplied++
        if (supplied < parameters.size) rest = rest.afterFirstWord()
    }
    return if (supplied == 0) parameters else parameters.drop(supplied)
}

/** Parse [raw] composer input into a [ChatCommand]. See the type doc for the rules. */
fun parseCommand(raw: String): ChatCommand {
    val trimmed = raw.trimAsciiWhitespace()
    val formatted = parseIrcFormatting(trimmed)
    val command = parsePlainCommand(formatted.visibleText)
    if (formatted.visibleText == trimmed) return command

    fun rawSuffix(plain: String): String {
        val visibleStart = formatted.visibleText.length - plain.length
        val rawStart = formatted.rawToVisible.indexOfFirst { it == visibleStart }.coerceAtLeast(0)
        val rawEnd = formatted.rawToVisible.indexOfLast { it == formatted.visibleText.length }
        return formatted.rawText.substring(rawStart, rawEnd)
    }

    fun formattedRawLine(command: ChatCommand.RawLine): ChatCommand.RawLine {
        val wireCommand = command.line.firstWord()
        val arguments = command.line.afterFirstWord()
        if (arguments.isEmpty()) return command
        val document = IrcEditorDocument.fromRaw(trimmed).first
        val visibleStart = document.text.length - arguments.length
        val formattedArguments =
            IrcEditorDocument(arguments, document.states.subList(visibleStart, document.text.length))
                .toRawValue(0, 0)
                .text
        return command.copy(line = "$wireCommand $formattedArguments")
    }

    if (command is ChatCommand.Message) {
        return if (command.text.startsWith("/me ")) {
            ChatCommand.Message("/me ${rawSuffix(command.text.removePrefix("/me "))}")
        } else {
            ChatCommand.Message(rawSuffix(command.text))
        }
    }
    return when (command) {
        is ChatCommand.Part -> command.copy(reason = command.reason?.let(::rawSuffix))
        is ChatCommand.Hop -> command.copy(reason = command.reason?.let(::rawSuffix))
        is ChatCommand.Msg -> command.copy(text = rawSuffix(command.text))
        is ChatCommand.Notice -> command.copy(text = rawSuffix(command.text))
        is ChatCommand.SetName -> command.copy(realname = rawSuffix(command.realname))
        is ChatCommand.Topic -> command.copy(topic = rawSuffix(command.topic))
        is ChatCommand.Away -> command.copy(message = command.message?.let(::rawSuffix))
        is ChatCommand.Kick -> command.copy(reason = command.reason?.let(::rawSuffix))
        is ChatCommand.Knock -> command.copy(reason = command.reason?.let(::rawSuffix))
        is ChatCommand.Ctcp -> command.copy(request = rawSuffix(command.request))
        is ChatCommand.RawLine -> formattedRawLine(command)
        else -> command
    }
}

/** Raw range where IRC formatting is valid; command names and routing parameters stay plain. */
internal fun messageFormattingRange(raw: String): IntRange? {
    if (plainIrcText(raw).isBlank()) return 0 until raw.length
    val first = raw.indexOfFirst { it != ' ' && it != '\t' && it != '\r' && it != '\n' }
    if (first < 0) return null
    val last = raw.indexOfLast { it != ' ' && it != '\t' && it != '\r' && it != '\n' }
    val trimmed = raw.substring(first, last + 1)
    val formatted = parseIrcFormatting(trimmed)
    val command = parsePlainCommand(formatted.visibleText)
    if (command is ChatCommand.Message && !formatted.visibleText.trimAsciiWhitespace().startsWith("/")) {
        return 0 until raw.length
    }
    val messageText =
        when (command) {
            is ChatCommand.Message -> command.text.removePrefix("/me ")
            is ChatCommand.Part -> command.reason
            is ChatCommand.Hop -> command.reason
            is ChatCommand.Msg -> command.text
            is ChatCommand.Notice -> command.text
            is ChatCommand.SetName -> command.realname
            is ChatCommand.Topic -> command.topic
            is ChatCommand.Away -> command.message
            is ChatCommand.Kick -> command.reason
            is ChatCommand.Knock -> command.reason
            is ChatCommand.Ctcp -> command.request
            is ChatCommand.RawLine -> command.line.afterFirstWord().ifEmpty { null }
            else -> null
        } ?: return null
    val plainMessage = plainIrcText(messageText)
    val visibleStart = formatted.visibleText.length - plainMessage.length
    val rawStart = formatted.rawToVisible.indexOfFirst { it == visibleStart }.coerceAtLeast(0)
    return first + rawStart until raw.length
}

private fun parsePlainCommand(raw: String): ChatCommand {
    val trimmed = raw.trimAsciiWhitespace()
    if (trimmed.isEmpty()) return ChatCommand.None

    // Not a command — ordinary message. `//text` escapes a literal leading slash.
    if (!trimmed.startsWith("/")) return ChatCommand.Message(trimmed)
    if (trimmed.startsWith("//")) return ChatCommand.Message(trimmed.substring(1))

    // Split "/cmd" from the remainder (single space, remainder kept raw for text args).
    val afterSlash = trimmed.substring(1)
    val space = afterSlash.indexOf(' ')
    val cmd = (if (space < 0) afterSlash else afterSlash.substring(0, space)).lowercase()
    val rest = if (space < 0) "" else afterSlash.substring(space + 1).trimAsciiWhitespace()

    // Bare "/" — nothing to send.
    if (cmd.isEmpty()) return ChatCommand.None

    return when (commandAliases[cmd] ?: cmd) {
        "me" -> {
            if (rest.isEmpty()) ChatCommand.None else ChatCommand.Message("/me $rest")
        }

        "join" -> {
            // JOIN's two parameters are positional lists: "#a,#b key-a,key-b".
            val channels = rest.firstWord()
            val keys = rest.afterFirstWord().firstWord().ifEmpty { null }
            if (channels.isEmpty()) ChatCommand.None else ChatCommand.Join(channels, keys)
        }

        "part" -> {
            ChatCommand.Part(rest.ifEmpty { null })
        }

        "hop" -> {
            ChatCommand.Hop(rest.ifEmpty { null })
        }

        "msg" -> {
            val nick = rest.firstWord()
            val text = rest.afterFirstWord()
            if (nick.isEmpty() || text.isEmpty()) ChatCommand.None else ChatCommand.Msg(nick, text)
        }

        "query" -> {
            val nick = rest.firstWord()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Query(nick)
        }

        "notice" -> {
            val target = rest.firstWord()
            val text = rest.afterFirstWord()
            if (target.isEmpty() || text.isEmpty()) ChatCommand.None else ChatCommand.Notice(target, text)
        }

        "nick" -> {
            val nick = rest.firstWord()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Nick(nick)
        }

        "setname" -> {
            if (rest.isEmpty()) ChatCommand.None else ChatCommand.SetName(rest)
        }

        "topic" -> {
            if (rest.isEmpty()) ChatCommand.None else ChatCommand.Topic(rest)
        }

        "mode" -> {
            parseMode(rest)
        }

        // `/away [msg]` sets away with a message; bare `/away` (and `/back`) clears it.
        "away" -> {
            ChatCommand.Away(rest.ifEmpty { null })
        }

        "back" -> {
            ChatCommand.Away(null)
        }

        "whois" -> {
            val nick = rest.firstWord()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Whois(nick)
        }

        "list" -> {
            ChatCommand.ChannelList
        }

        "kick" -> {
            val nick = rest.firstWord()
            val reason = rest.afterFirstWord().ifEmpty { null }
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Kick(nick, reason)
        }

        "ban" -> {
            val nick = rest.firstWord()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Ban(nick)
        }

        "op", "deop" -> {
            ChatCommand.ChannelOperator(rest.firstWord().ifEmpty { null }, grant = cmd == "op")
        }

        "invite" -> {
            val nick = rest.firstWord()
            val channel = rest.afterFirstWord().firstWord().ifEmpty { null }
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Invite(nick, channel)
        }

        "knock" -> {
            val channel = rest.firstWord()
            val reason = rest.afterFirstWord().ifEmpty { null }
            if (channel.isEmpty()) ChatCommand.None else ChatCommand.Knock(channel, reason)
        }

        "ctcp" -> {
            val nick = rest.firstWord()
            val request = rest.afterFirstWord()
            if (nick.isEmpty() || request.isEmpty()) ChatCommand.None else ChatCommand.Ctcp(nick, request)
        }

        "motd" -> {
            ChatCommand.Motd(rest.firstWord().ifEmpty { null })
        }

        // Explicit escape hatch, so a line can reach the server even when its first word collides
        // with a command handled above.
        "raw" -> {
            if (rest.isEmpty()) ChatCommand.None else ChatCommand.RawLine(rest)
        }

        // Unknown command: pass through raw, slash stripped (e.g. "names").
        else -> {
            ChatCommand.RawLine(afterSlash)
        }
    }
}

/**
 * `/mode`, `/mode +o nick`, `/mode #chan +o nick`, `/mode #chan`.
 *
 * A leading `+`/`-` token is always a mode string, so anything else in that position is the target.
 * The one case this cannot disambiguate is a `+`-prefixed (modeless) channel name — which by
 * definition takes no modes anyway; reach one with `/raw MODE` if a server still offers them.
 */
private fun parseMode(rest: String): ChatCommand {
    if (rest.isEmpty()) return ChatCommand.Mode(target = null, modes = null)
    val first = rest.firstWord()
    if (first.startsWith("+") || first.startsWith("-")) {
        return ChatCommand.Mode(target = null, modes = rest)
    }
    return ChatCommand.Mode(target = first, modes = rest.afterFirstWord().ifEmpty { null })
}

private fun String.firstWord(): String = substringBefore(' ').trimAsciiWhitespace()

private fun String.afterFirstWord(): String = substringAfter(' ', "").trimAsciiWhitespace()

private fun String.trimAsciiWhitespace(): String = trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
