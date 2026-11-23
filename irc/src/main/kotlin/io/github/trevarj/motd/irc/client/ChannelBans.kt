package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.transport.IrcTransport
import kotlinx.coroutines.CompletableDeferred

data class ChannelBanEntry(
    val mask: String,
    val setter: String? = null,
    val setAtEpochSeconds: Long? = null,
)

sealed interface ChannelBanListResult {
    data class Complete(
        val entries: List<ChannelBanEntry>,
    ) : ChannelBanListResult

    data object Denied : ChannelBanListResult

    data object Incomplete : ChannelBanListResult

    data object Unavailable : ChannelBanListResult
}

/** One literal IRC middle parameter, with no serializer rewriting or UTF-8 substitution. */
internal fun String.isBanModeParameter(): Boolean =
    length in 1..510 && first() != ':' &&
        indices.none { index ->
            val char = this[index]
            char == ' ' || char.isISOControl() ||
                (char.isHighSurrogate() && getOrNull(index + 1)?.isLowSurrogate() != true) ||
                (char.isLowSurrogate() && getOrNull(index - 1)?.isHighSurrogate() != true)
        }

/** Serialization enforces the 512-byte IRC message limit, including CRLF. */
internal fun channelBanModeLine(
    channel: String,
    mask: String? = null,
    label: String? = null,
): String? {
    if (!channel.isBanModeParameter() || ',' in channel || mask?.isBanModeParameter() == false) return null
    val params = if (mask == null) listOf(channel, "+b") else listOf(channel, "-b", mask)
    val tags = if (label == null) emptyMap() else mapOf("label" to label)
    return try {
        IrcMessage(tags = tags, command = "MODE", params = params).serialize()
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun IrcMessage.isBanEnd(): Boolean = command == "368" && params.size == 3

private fun IrcMessage.isBanDenial(): Boolean =
    when (command) {
        "403", "442", "482" -> params.size == 3
        "FAIL" -> params.size >= 4 && params[0].equals("MODE", ignoreCase = true)
        else -> false
    }

private fun IrcMessage.banReplyTarget(
    nick: String,
    mapping: IrcCaseMapping,
): String? =
    when (command) {
        "367", "368", "403", "442", "482" -> {
            params.getOrNull(1)?.takeIf { params.firstOrNull()?.let(mapping::normalize) == mapping.normalize(nick) }
        }

        "FAIL" -> {
            params.getOrNull(2)?.takeIf { isBanDenial() }
        }

        else -> {
            null
        }
    }

internal class ChannelBanRequest(
    val channel: String,
    val label: String?,
) {
    val stopped = CompletableDeferred<ChannelBanListResult>()
    val rows = ChannelBanRows()

    fun acceptsLabeled(
        message: IrcMessage,
        nick: String,
        mapping: IrcCaseMapping,
    ): Boolean {
        val target = message.banReplyTarget(nick, mapping)
        if (target != null) return mapping.normalize(target) == mapping.normalize(channel)
        // A matched label can identify a malformed row or terminator missing a channel, but never
        // one addressed to a different recipient or channel.
        return (message.command == "367" || message.command == "368") && message.params.size < 2 &&
            (message.params.isEmpty() || message.params[0].let(mapping::normalize) == mapping.normalize(nick))
    }
}

internal class ChannelBanRows {
    private val entries = mutableListOf<ChannelBanEntry>()
    private var denied = false
    private var ended = false
    private var malformed = false

    fun accept(message: IrcMessage) {
        when {
            message.isBanDenial() -> {
                denied = true
            }

            message.command == "403" || message.command == "442" || message.command == "482" -> {
                malformed = true
            }

            message.command == "367" -> {
                val params = message.params
                val mask = params.getOrNull(2)
                val setter = params.getOrNull(3)
                val timestamp = params.getOrNull(4)?.toLongOrNull()?.takeIf { it >= 0 }
                if (ended || params.size !in 3..5 || mask == null || !mask.isBanModeParameter() ||
                    setter?.isBanModeParameter() == false || (params.size == 5 && timestamp == null) || entries.size >= MAX_ENTRIES
                ) {
                    malformed = true
                } else {
                    entries += ChannelBanEntry(mask, setter, timestamp)
                }
            }

            message.command == "368" -> {
                if (message.isBanEnd() && !ended) ended = true else malformed = true
            }
        }
    }

    fun result(): ChannelBanListResult =
        when {
            denied -> ChannelBanListResult.Denied
            ended && !malformed -> ChannelBanListResult.Complete(entries.toList())
            else -> ChannelBanListResult.Incomplete
        }

    private companion object {
        const val MAX_ENTRIES = 4_096
    }
}

private val defaultChannelModeGroups = listOf("b", "k", "l", "imnpst")

private fun IrcMessage.mayReadBanList(
    modes: String,
    lastBan: Int,
    isupport: Isupport,
): Boolean {
    if (params.size == 2 || !modes.isBanModeParameter()) return true
    val (listModes, paramModes, setParamModes, flagModes) =
        isupport["CHANMODES"]?.split(',')?.takeIf { it.size >= 4 } ?: defaultChannelModeGroups
    val prefixes = isupport.prefixModes
    var adding = true
    var argIndex = 2
    for (index in 0..lastBan) {
        val mode = modes[index]
        when (mode) {
            '+' -> {
                adding = true
            }

            '-' -> {
                adding = false
            }

            else -> {
                val consumesArg =
                    when {
                        mode == 'b' || prefixes.any { it.first == mode } || mode in listModes || mode in paramModes -> true
                        mode in setParamModes -> adding
                        mode in flagModes -> false
                        else -> true // An unknown mode may take the argument before a ban mode.
                    }
                if (!consumesArg) continue
                if (argIndex >= params.size) return true
                // A malformed middle parameter can change how many arguments the server sees.
                if (argIndex < params.lastIndex && !params[argIndex].isBanModeParameter()) return true
                argIndex++
            }
        }
    }
    return false
}

/** Connection-owned tracker for MODE +b; never fences upstream replies with a bouncer PONG. */
internal class ChannelBanRequests(
    val transport: IrcTransport,
) {
    private var active: ChannelBanRequest? = null
    private var closed = false

    // One 368 per abandoned/raw query, including a denial with an optional end. Without that end
    // a same-channel response is ambiguous for this socket; never evict a debt to start a query.
    private val drains = mutableMapOf<String, Int>()
    private var unlabeledBlocked = false
    private var caseMapping: IrcCaseMapping = IrcCaseMapping.Rfc1459

    @Synchronized
    fun begin(
        request: ChannelBanRequest,
        mapping: IrcCaseMapping,
    ): Boolean {
        updateMapping(mapping)
        if (closed || active != null) return false
        if (request.label == null && (unlabeledBlocked || mapping.normalize(request.channel) in drains)) return false
        active = request
        return true
    }

    @Synchronized
    fun abandon(request: ChannelBanRequest) {
        if (active !== request) return
        active = null
        if (request.label == null) remember(caseMapping.normalize(request.channel))
    }

    @Synchronized
    fun left(
        channel: String,
        mapping: IrcCaseMapping,
    ) {
        updateMapping(mapping)
        val request = active ?: return
        if (mapping.normalize(request.channel) != mapping.normalize(channel)) return
        interrupt(request)
    }

    @Synchronized
    fun close() {
        closed = true
        val request = active
        active = null
        drains.clear()
        request?.stopped?.complete(ChannelBanListResult.Incomplete)
    }

    /** Track potentially competing raw ban-list reads without blocking raw writes or hiding replies. */
    @Synchronized
    fun rawMode(
        message: IrcMessage,
        isupport: Isupport,
    ) {
        val modes = message.params.getOrNull(1) ?: return
        val lastBan = modes.lastIndexOf('b')
        if (closed || lastBan < 0) return
        val singleBanMode = modes == "b" || modes == "+b" || modes == "-b"
        if (singleBanMode && message.params.size > 2) return
        if (!singleBanMode && !message.mayReadBanList(modes, lastBan, isupport)) return
        val targets = message.params[0].split(',')
        val mapping = isupport.identityRules.caseMapping
        updateMapping(mapping)
        for (target in targets) {
            if (!target.isBanModeParameter()) continue
            val key = mapping.normalize(target)
            active?.takeIf { it.label == null && mapping.normalize(it.channel) == key }?.let(::interrupt)
            remember(key, exact = singleBanMode && targets.size == 1)
        }
    }

    @Synchronized
    fun routeUnlabeled(
        message: IrcMessage,
        nick: String,
        mapping: IrcCaseMapping,
    ): Boolean {
        if (closed || "label" in message.tags || "batch" in message.tags) return false
        updateMapping(mapping)
        val target = message.banReplyTarget(nick, mapping)?.let(mapping::normalize)
        if (target == null) {
            val request = active
            if (request != null && request.label == null && (message.command == "367" || message.command == "368") &&
                message.params.size < 2 &&
                (message.params.isEmpty() || mapping.normalize(message.params[0]) == mapping.normalize(nick))
            ) {
                request.rows.accept(message)
            }
            return false
        }
        val debt = drains[target]
        if (debt != null) {
            if (message.isBanEnd() && debt != Int.MAX_VALUE) {
                if (debt == 1) drains.remove(target) else drains[target] = debt - 1
            }
            return false
        }
        val request = active ?: return false
        if (request.label != null || mapping.normalize(request.channel) != target) return false
        request.rows.accept(message)
        if (message.isBanEnd() || message.isBanDenial()) {
            active = null
            if (message.isBanDenial()) remember(target)
            request.stopped.complete(request.rows.result())
        }
        return true
    }

    private fun interrupt(request: ChannelBanRequest) {
        abandon(request)
        request.stopped.complete(ChannelBanListResult.Incomplete)
    }

    @Synchronized
    fun updateMapping(mapping: IrcCaseMapping) {
        if (caseMapping == mapping) return
        caseMapping = mapping
        if (drains.isNotEmpty() || active?.let { it.label == null } == true) {
            unlabeledBlocked = true
            drains.clear()
        }
        active?.let(::interrupt)
    }

    private fun remember(
        channel: String,
        exact: Boolean = true,
    ) {
        if (unlabeledBlocked) return
        val debt = drains[channel] ?: 0
        if (debt == 0 && drains.size == MAX_DRAINS) {
            unlabeledBlocked = true
            drains.clear()
            return
        }
        drains[channel] = if (!exact || debt == Int.MAX_VALUE) Int.MAX_VALUE else debt + 1
    }

    private companion object {
        const val MAX_DRAINS = 64
    }
}
