package io.github.trevarj.motd.irc.proto

/** Parsed IRC prefix (`:nick!user@host`). */
data class Prefix(val nick: String, val user: String? = null, val host: String? = null)

/** One IRC protocol message. Tags are unescaped values; empty map when absent. */
data class IrcMessage(
    val tags: Map<String, String> = emptyMap(),
    val source: Prefix? = null,
    val command: String,
    val params: List<String> = emptyList(),
) {
    companion object {
        /** Parse a single line WITHOUT trailing CRLF. Throws IrcParseException on garbage. */
        fun parse(line: String): IrcMessage = TODO("WP2")
    }

    /** Serialize to wire format WITHOUT trailing CRLF. Escapes tag values. */
    fun serialize(): String = TODO("WP2")
}

class IrcParseException(message: String, val line: String) : Exception(message)

/** RPL_ISUPPORT (005) accumulator. */
class Isupport {
    fun update(tokens: List<String>): Unit = TODO("WP2")        // params from a 005, minus nick + trailing text
    operator fun get(key: String): String? = TODO("WP2")        // e.g. get("CHATHISTORY") -> "1000"
    val caseMapping: String get() = TODO("WP2")                 // default "rfc1459"
    val chanTypes: String get() = TODO("WP2")                   // default "#&"
    val prefixModes: List<Pair<Char, Char>> get() = TODO("WP2") // mode->prefix pairs, e.g. (o,'@'), (v,'+')

    /** Case-normalize a nick/channel per CASEMAPPING for map keys and comparisons. */
    fun normalize(name: String): String = TODO("WP2")
}
