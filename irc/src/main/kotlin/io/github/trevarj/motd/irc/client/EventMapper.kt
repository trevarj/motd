package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.proto.parseIrcPrefix
import io.github.trevarj.motd.irc.proto.reactionValue
import io.github.trevarj.motd.irc.proto.replyReference
import io.github.trevarj.motd.irc.proto.unreactionValue
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Maps an inbound [IrcMessage] to a domain [IrcEvent] (plans/02 mapping table).
 *
 * Stateful only for NAMES accumulation (353 lines collected until 366). Everything else is a
 * pure function of the message plus the current self-nick / isupport passed in from the client.
 */
internal class EventMapper(
    /** Returns the client's current nick (tracked through NICK changes). */
    private val selfNick: () -> String,
    private val isupport: () -> Isupport,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    // channel(normalized) -> accumulating members for a pending NAMES (353..366) run.
    private val namesBuffers = HashMap<String, MutableList<IrcEvent.Names.Member>>()
    private val namesDisplay = HashMap<String, String>()

    private fun ctx(msg: IrcMessage, batchId: String?): MessageContext = MessageContext(
        msgid = msg.tags["msgid"],
        serverTime = parseServerTime(msg.tags["time"]),
        account = msg.tags["account"],
        batchId = batchId ?: msg.tags["batch"],
        label = msg.tags["label"],
    )

    private fun parseServerTime(time: String?): Long {
        if (time == null) return now()
        return try {
            Instant.parse(time).toEpochMilli()
        } catch (_: DateTimeParseException) {
            now()
        }
    }

    private fun isSelf(source: Prefix?): Boolean {
        val nick = source?.nick ?: return false
        return isupport().normalize(nick) == isupport().normalize(selfNick())
    }

    /**
     * Map one message. Returns null when the message produces no event yet (e.g. an
     * intermediate NAMES 353 line, or a CTCP that was auto-answered).
     *
     * [ctcpReply] is invoked (fire-and-forget) with a NOTICE line to answer CTCP VERSION.
     */
    fun map(msg: IrcMessage, batchId: String? = null, ctcpReply: (IrcMessage) -> Unit = {}): IrcEvent? {
        val c = { ctx(msg, batchId) }
        return when (msg.command) {
            "PRIVMSG", "NOTICE" -> mapChat(msg, c(), ctcpReply)
            "TAGMSG" -> mapTagMessage(msg, c())
            "JOIN" -> mapJoin(msg, c())
            "PART" -> {
                val nick = msg.source?.nick ?: return IrcEvent.Raw(msg)
                val channel = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
                IrcEvent.Parted(c(), nick, channel, msg.params.getOrNull(1), isSelf(msg.source))
            }
            "QUIT" -> {
                val nick = msg.source?.nick ?: return IrcEvent.Raw(msg)
                IrcEvent.Quit(c(), nick, msg.params.getOrNull(0))
            }
            "KICK" -> {
                val channel = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
                val victim = msg.params.getOrNull(1) ?: return IrcEvent.Raw(msg)
                val by = msg.source?.nick ?: ""
                IrcEvent.Kicked(
                    c(), victim, channel, by, msg.params.getOrNull(2),
                    isSelf = isupport().normalize(victim) == isupport().normalize(selfNick()),
                )
            }
            "NICK" -> {
                val from = msg.source?.nick ?: return IrcEvent.Raw(msg)
                val to = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
                IrcEvent.NickChanged(c(), from, to, isSelf(msg.source))
            }
            "TOPIC" -> {
                val channel = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
                IrcEvent.TopicChanged(c(), channel, msg.params.getOrNull(1).orEmpty(), msg.source?.nick)
            }
            "MODE" -> {
                val target = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
                val modes = msg.params.getOrNull(1).orEmpty()
                IrcEvent.ModeChanged(c(), target, modes, msg.params.drop(2))
            }
            "INVITE" -> {
                val nick = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
                val channel = msg.params.getOrNull(1) ?: return IrcEvent.Raw(msg)
                IrcEvent.Invited(c(), msg.source?.nick ?: "", nick, channel)
            }
            "AWAY" -> {
                val nick = msg.source?.nick ?: return IrcEvent.Raw(msg)
                IrcEvent.AwayChanged(nick, msg.params.getOrNull(0))
            }
            "ACCOUNT" -> {
                val nick = msg.source?.nick ?: return IrcEvent.Raw(msg)
                val acct = msg.params.getOrNull(0)
                IrcEvent.AccountChanged(nick, if (acct == null || acct == "*") null else acct)
            }
            "CHGHOST" -> {
                val nick = msg.source?.nick ?: return IrcEvent.Raw(msg)
                val user = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
                val host = msg.params.getOrNull(1) ?: return IrcEvent.Raw(msg)
                IrcEvent.HostChanged(nick, user, host)
            }
            "SETNAME" -> {
                val nick = msg.source?.nick ?: return IrcEvent.Raw(msg)
                IrcEvent.RealnameChanged(nick, msg.params.getOrNull(0).orEmpty())
            }
            "MARKREAD" -> mapMarkRead(msg)
            "BOUNCER" -> mapBouncer(msg)
            "ERROR" -> IrcEvent.ServerError("ERROR", msg.params, msg.params.lastOrNull().orEmpty())
            // NAMES accumulation.
            "353" -> if (accumulateNames(msg)) {
                val channel = msg.params.getOrNull(2) ?: msg.params.getOrNull(1).orEmpty()
                IrcEvent.NamesStarted(channel)
            } else null
            "366" -> finishNames(msg)
            "354" -> mapWhox(msg)
            "315" -> IrcEvent.WhoxComplete(msg.params.getOrNull(1).orEmpty())
            "730" -> IrcEvent.MonitorOnline(
                monitorTargets(msg).mapNotNull(::parseIrcPrefix),
            )
            "731" -> IrcEvent.MonitorOffline(monitorTargets(msg))
            "732" -> IrcEvent.MonitorList(monitorTargets(msg))
            "733" -> IrcEvent.MonitorListEnd
            "734" -> IrcEvent.MonitorLimitExceeded(
                limit = msg.params.getOrNull(1)?.toIntOrNull(),
                targets = msg.params.getOrNull(2).orEmpty().split(',').filter(String::isNotBlank),
                text = msg.params.lastOrNull().orEmpty(),
            )
            else -> mapNumericOrRaw(msg)
        }
    }

    private fun mapChat(msg: IrcMessage, ctx: MessageContext, ctcpReply: (IrcMessage) -> Unit): IrcEvent? {
        val source = msg.source ?: Prefix(nick = "")
        val target = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
        var text = msg.params.getOrNull(1).orEmpty()
        val kind: IrcEvent.ChatKind

        // CTCP handling: \x01 ... \x01
        if (text.length >= 2 && text.first() == '' && text.last() == '') {
            val inner = text.substring(1, text.length - 1)
            when {
                inner.startsWith("ACTION ") -> {
                    text = inner.removePrefix("ACTION ")
                    kind = IrcEvent.ChatKind.ACTION
                }
                inner == "ACTION" -> {
                    text = ""
                    kind = IrcEvent.ChatKind.ACTION
                }
                inner == "VERSION" && msg.command == "PRIVMSG" -> {
                    // Answer CTCP VERSION with a NOTICE; emit nothing.
                    ctcpReply(
                        IrcMessage(
                            command = "NOTICE",
                            params = listOf(source.nick, "VERSION MOTD"),
                        ),
                    )
                    return null
                }
                else -> return null // ignore other CTCP
            }
        } else {
            kind = if (msg.command == "NOTICE") IrcEvent.ChatKind.NOTICE else IrcEvent.ChatKind.PRIVMSG
        }

        return IrcEvent.ChatMessage(
            ctx = ctx,
            kind = kind,
            source = source,
            target = target,
            text = text,
            isSelf = isSelf(msg.source),
            replyToMsgid = msg.replyReference(),
        )
    }

    private fun mapTagMessage(msg: IrcMessage, ctx: MessageContext): IrcEvent {
        val source = msg.source ?: Prefix(nick = "")
        val target = msg.params.getOrNull(0).orEmpty()
        val react = msg.reactionValue()
        val unreact = msg.unreactionValue()
        // Unreact travels as Raw so the app's sole Room writer owns the idempotent delete for both
        // live and CHATHISTORY paths without adding a second reaction-mutation boundary here.
        if (unreact != null) return IrcEvent.Raw(msg)
        val replyTag = msg.replyReference()
        return IrcEvent.TagMessage(
            ctx = ctx,
            source = source,
            target = target,
            typing = msg.tags["+typing"],
            reactEmoji = react,
            reactTargetMsgid = if (react != null) replyTag else null,
        )
    }

    private fun mapJoin(msg: IrcMessage, ctx: MessageContext): IrcEvent {
        val nick = msg.source?.nick ?: return IrcEvent.Raw(msg)
        val channel = msg.params.getOrNull(0) ?: return IrcEvent.Raw(msg)
        // extended-join: JOIN <channel> <account> :<realname>
        val account = msg.params.getOrNull(1)?.let { if (it == "*") null else it }
        val realname = msg.params.getOrNull(2)
        return IrcEvent.Joined(ctx, nick, channel, account, realname, isSelf(msg.source))
    }

    private fun mapMarkRead(msg: IrcMessage): IrcEvent {
        val target = msg.params.getOrNull(0).orEmpty()
        val tsToken = msg.params.getOrNull(1) // "timestamp=<ISO>" or "timestamp=*"
        val value = tsToken?.substringAfter("timestamp=", "")
        val ts = when {
            value.isNullOrEmpty() || value == "*" -> null
            else -> try {
                Instant.parse(value).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
        return IrcEvent.ReadMarker(target, ts)
    }

    private fun mapBouncer(msg: IrcMessage): IrcEvent {
        // BOUNCER NETWORK <netid> <attrs|*>
        if (msg.params.getOrNull(0) == "NETWORK") {
            val netId = msg.params.getOrNull(1) ?: return IrcEvent.Raw(msg)
            val attrsRaw = msg.params.getOrNull(2).orEmpty()
            val attrs = if (attrsRaw == "*") emptyMap() else parseAttrs(attrsRaw)
            return IrcEvent.BouncerNetworkState(netId, attrs)
        }
        return IrcEvent.Raw(msg)
    }

    private fun accumulateNames(msg: IrcMessage): Boolean {
        // 353: <nick> <symbol> <channel> :<members>
        val channel = msg.params.getOrNull(2) ?: msg.params.getOrNull(1) ?: return false
        val key = isupport().normalize(channel)
        val started = key !in namesBuffers
        namesDisplay[key] = channel
        val members = namesBuffers.getOrPut(key) { mutableListOf() }
        val list = msg.params.lastOrNull().orEmpty().split(' ').filter { it.isNotEmpty() }
        val prefixChars = isupport().prefixModes.map { it.second }.toSet()
        for (entry in list) {
            var i = 0
            val prefixes = StringBuilder()
            while (i < entry.length && entry[i] in prefixChars) {
                prefixes.append(entry[i]); i++
            }
            val rest = entry.substring(i)
            val identity = parseIrcPrefix(rest) ?: continue
            members.add(
                IrcEvent.Names.Member(
                    identity.nick,
                    prefixes.toString(),
                    identity.user,
                    identity.host,
                ),
            )
        }
        return started
    }

    private fun finishNames(msg: IrcMessage): IrcEvent {
        val channel = msg.params.getOrNull(1) ?: msg.params.getOrNull(0).orEmpty()
        val key = isupport().normalize(channel)
        val members = namesBuffers.remove(key) ?: mutableListOf()
        val display = namesDisplay.remove(key) ?: channel
        return IrcEvent.Names(display, members)
    }

    private fun mapWhox(msg: IrcMessage): IrcEvent {
        // WHO <mask> %tuhnafr,<token> yields:
        // 354 <client> <token> <user> <host> <nick> <account> <flags> :<realname>
        val token = msg.params.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 0..999 }
            ?: return IrcEvent.Raw(msg)
        val username = msg.params.getOrNull(2)
        val host = msg.params.getOrNull(3)
        val nick = msg.params.getOrNull(4) ?: return IrcEvent.Raw(msg)
        val accountRaw = msg.params.getOrNull(5)
        val flags = msg.params.getOrNull(6)
        val realname = msg.params.getOrNull(7)
        val account = accountRaw?.takeUnless { it == "0" || it == "*" }
        return IrcEvent.WhoxRow(token, username, host, nick, account, flags, realname)
    }

    private fun mapNumericOrRaw(msg: IrcMessage): IrcEvent {
        // Error numerics (4xx/5xx) surface as ServerError; everything else is Raw.
        val cmd = msg.command
        if (cmd.length == 3 && cmd.all { it.isDigit() } && (cmd[0] == '4' || cmd[0] == '5')) {
            return IrcEvent.ServerError(cmd, msg.params, msg.params.lastOrNull().orEmpty())
        }
        return IrcEvent.Raw(msg)
    }

    private fun monitorTargets(msg: IrcMessage): List<String> =
        msg.params.drop(1).firstOrNull().orEmpty().split(',').filter(String::isNotBlank)

    /** Parse tag-escaped `k=v;k2=v2` attribute strings (bouncer / webpush). */
    private fun parseAttrs(raw: String): Map<String, String> = parseAttrString(raw)
}

/** Shared `k=v;k2=v2` parser with IRCv3 tag-value unescaping. */
internal fun parseAttrString(raw: String): Map<String, String> {
    if (raw.isEmpty() || raw == "*") return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in raw.split(';')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        if (eq < 0) {
            out[part] = ""
        } else {
            out[part.substring(0, eq)] = unescapeAttrValue(part.substring(eq + 1))
        }
    }
    return out
}

private fun unescapeAttrValue(value: String): String {
    if (value.indexOf('\\') < 0) return value
    val sb = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch != '\\') { sb.append(ch); i++; continue }
        if (i == value.length - 1) break
        when (val next = value[i + 1]) {
            ':' -> sb.append(';')
            's' -> sb.append(' ')
            '\\' -> sb.append('\\')
            'r' -> sb.append('\r')
            'n' -> sb.append('\n')
            else -> sb.append(next)
        }
        i += 2
    }
    return sb.toString()
}
