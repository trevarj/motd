package io.github.trevarj.motd.irc.proto

/** The server's rules for comparing IRC identifiers such as nicks and channel names. */
sealed class IrcCaseMapping {
    /** Canonical protocol name, or the exact advertised value for [Unknown]. */
    abstract val rawName: String

    /** Diagnostic for a mapping whose full semantics are not supported. */
    open val diagnostic: String? = null

    abstract fun normalize(value: String): String

    data object Ascii : IrcCaseMapping() {
        override val rawName: String = "ascii"

        override fun normalize(value: String): String = fold(value)
    }

    data object Rfc1459 : IrcCaseMapping() {
        override val rawName: String = "rfc1459"

        override fun normalize(value: String): String =
            fold(value, foldBrackets = true, foldTilde = true)
    }

    data object Rfc1459Strict : IrcCaseMapping() {
        override val rawName: String = "rfc1459-strict"

        override fun normalize(value: String): String = fold(value, foldBrackets = true)
    }

    /** Unknown mappings use ASCII folding rather than merging extra identities speculatively. */
    data class Unknown(override val rawName: String) : IrcCaseMapping() {
        override val diagnostic: String =
            "Unsupported IRC CASEMAPPING '$rawName'; using conservative ASCII folding"

        override fun normalize(value: String): String = fold(value)
    }

    companion object {
        /** Missing CASEMAPPING has the RFC1459 default required for legacy interoperability. */
        fun from(rawName: String?): IrcCaseMapping {
            if (rawName == null) return Rfc1459
            return when (rawName.lowercase()) {
                "ascii" -> Ascii
                "rfc1459" -> Rfc1459
                "rfc1459-strict" -> Rfc1459Strict
                else -> Unknown(rawName)
            }
        }
    }
}

/** Immutable identity and channel-classification rules derived from one ISUPPORT snapshot. */
data class IrcIdentityRules(
    val caseMapping: IrcCaseMapping = IrcCaseMapping.Rfc1459,
    /** Null means CHANTYPES was missing; an empty string means it was explicitly valueless. */
    val advertisedChanTypes: String? = null,
) {
    /** Missing CHANTYPES uses the RFC1459 default; any advertised value is authoritative. */
    val chanTypes: String
        get() = advertisedChanTypes ?: DEFAULT_CHAN_TYPES

    fun normalize(name: String): String = caseMapping.normalize(name)

    fun isChannel(target: String): Boolean = target.firstOrNull()?.let { it in chanTypes } == true

    /** Stable reaction identity: authenticated accounts win, otherwise use the network casemap. */
    fun actorKey(nick: String, account: String?): String =
        account?.takeUnless { it.isEmpty() || it == "*" }?.let { "account:$it" }
            ?: "nick:${normalize(nick)}"

    /** Mention matching uses IRC casemapping while retaining the existing word-boundary contract. */
    fun containsMention(text: String, identity: String): Boolean {
        if (identity.isEmpty() || text.length < identity.length) return false
        val normalizedIdentity = normalize(identity)
        val lastStart = text.length - identity.length
        for (start in 0..lastStart) {
            val end = start + identity.length
            if (start > 0 && text[start - 1].isMentionWordCharacter()) continue
            if (end < text.length && text[end].isMentionWordCharacter()) continue
            if (normalize(text.substring(start, end)) == normalizedIdentity) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_CHAN_TYPES: String = "#&"

        fun from(rawCaseMapping: String?, advertisedChanTypes: String?): IrcIdentityRules =
            IrcIdentityRules(
                caseMapping = IrcCaseMapping.from(rawCaseMapping),
                advertisedChanTypes = advertisedChanTypes,
            )
    }
}

private fun Char.isMentionWordCharacter(): Boolean = this == '_' || isLetterOrDigit()

private fun fold(value: String, foldBrackets: Boolean = false, foldTilde: Boolean = false): String =
    buildString(value.length) {
        for (char in value) {
            append(
                when {
                    char in 'A'..'Z' -> char + 32
                    foldBrackets && char == '[' -> '{'
                    foldBrackets && char == ']' -> '}'
                    foldBrackets && char == '\\' -> '|'
                    foldTilde && char == '~' -> '^'
                    else -> char
                },
            )
        }
    }
