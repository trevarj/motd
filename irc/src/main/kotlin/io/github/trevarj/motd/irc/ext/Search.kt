package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.time.Instant

/** soju caps SEARCH responses at 100 messages regardless of the requested limit. */
const val SOJU_SEARCH_MAX_LIMIT = 100

/**
 * One soju.im/search query. [target] (`in`) is mandatory: soju rejects a SEARCH without it and has
 * no cross-buffer search. Text semantics are store-dependent (the fs store does a case-insensitive
 * substring match; the sqlite store does an FTS5 whole-token match), so callers must not promise
 * either one to the user.
 */
data class SearchRequest(
    val target: String,
    val text: String? = null,
    /** Exact nick, matched server-side. */
    val from: String? = null,
    /** Epoch milliseconds; results are strictly older than this. */
    val before: Long? = null,
    /** Epoch milliseconds; results are strictly newer than this. */
    val after: Long? = null,
    val limit: Int = SOJU_SEARCH_MAX_LIMIT,
)

enum class SearchResultKind { PRIVMSG, NOTICE, ACTION }

/**
 * One transient SEARCH result. Deliberately NOT an [io.github.trevarj.motd.irc.event.IrcEvent] and
 * never persisted: search results carry no interval semantics for the history graph, so writing
 * them into the timeline would assert coverage the server never claimed. Jumping to a hit's context
 * goes through CHATHISTORY AROUND, which does carry those semantics.
 */
data class SearchResultMessage(
    val target: String,
    val sender: String,
    val text: String,
    val kind: SearchResultKind,
    /** Null when the line carried no `time` tag; the hit is then msgid-only. */
    val serverTime: Long?,
    val msgid: String?,
    /** IRCv3 account identity; missing, empty and logged-out (`*`) tags are absent. */
    val senderAccount: String? = null,
)

/**
 * Builds the `SEARCH` line. Attributes are a single `key=value;key=value` parameter in soju's
 * `irc.ParseTags` form, so every value goes through the IRCv3 tag escape table.
 */
internal object SearchCommands {
    fun search(req: SearchRequest): IrcMessage {
        require(req.target.isNotBlank()) { "SEARCH requires a target" }
        val attributes =
            buildList {
                add("in" to req.target)
                req.text?.takeIf { it.isNotBlank() }?.let { add("text" to it) }
                req.from?.takeIf { it.isNotBlank() }?.let { add("from" to it) }
                req.after?.let { add("after" to ChatHistorySelectors.isoTimestamp(it)) }
                req.before?.let { add("before" to ChatHistorySelectors.isoTimestamp(it)) }
                add("limit" to req.limit.coerceIn(1, SOJU_SEARCH_MAX_LIMIT).toString())
            }.joinToString(";") { (key, value) -> "$key=${IrcMessage.escapeTagValue(value)}" }
        return IrcMessage(command = "SEARCH", params = listOf(attributes))
    }
}

/** Maps one line of a `soju.im/search` batch, or null when it is not a result message. */
internal fun parseSearchResult(message: IrcMessage): SearchResultMessage? {
    if (message.command != "PRIVMSG" && message.command != "NOTICE") return null
    val sender = message.source?.nick ?: return null
    val target = message.params.getOrNull(0) ?: return null
    var text = message.params.getOrNull(1).orEmpty()
    var kind = if (message.command == "NOTICE") SearchResultKind.NOTICE else SearchResultKind.PRIVMSG
    // CTCP ACTION: \x01ACTION <text>[\x01]. The trailing delimiter is optional in the wild.
    if (text.startsWith('\u0001')) {
        val inner = text.removePrefix("\u0001").removeSuffix("\u0001")
        when {
            inner.startsWith("ACTION ") -> {
                text = inner.removePrefix("ACTION ")
                kind = SearchResultKind.ACTION
            }

            inner == "ACTION" -> {
                text = ""
                kind = SearchResultKind.ACTION
            }
        }
    }
    return SearchResultMessage(
        target = target,
        sender = sender,
        text = text,
        kind = kind,
        serverTime =
            message.tags["time"]?.let { encoded ->
                runCatching { Instant.parse(encoded).toEpochMilli() }.getOrNull()
            },
        msgid = message.tags["msgid"]?.takeIf(String::isNotEmpty),
        senderAccount = message.tags["account"]?.takeIf { it.isNotBlank() && it != "*" },
    )
}
