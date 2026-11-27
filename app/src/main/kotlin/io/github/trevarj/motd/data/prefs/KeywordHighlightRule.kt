package io.github.trevarj.motd.data.prefs

import kotlinx.serialization.Serializable

@Serializable
data class KeywordHighlightRule(
    val phrase: String,
    val alerts: Boolean,
)

enum class KeywordMatch { NONE, SILENT, ALERT }

internal const val MAX_KEYWORD_HIGHLIGHT_RULES = 32
internal const val MAX_KEYWORD_HIGHLIGHT_LENGTH = 80

internal enum class KeywordRuleError { INVALID_PHRASE, DUPLICATE, LIMIT }

internal fun keywordHighlightRuleError(
    term: String,
    otherRules: List<KeywordHighlightRule>,
): KeywordRuleError? =
    when {
        term.isEmpty() || term.length > MAX_KEYWORD_HIGHLIGHT_LENGTH ||
            term.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } -> KeywordRuleError.INVALID_PHRASE

        otherRules.any { matchKeywordEnd(it.phrase, 0, term) == it.phrase.length } -> KeywordRuleError.DUPLICATE

        otherRules.size >= MAX_KEYWORD_HIGHLIGHT_RULES -> KeywordRuleError.LIMIT

        else -> null
    }

internal fun validateKeywordHighlightRules(rules: List<KeywordHighlightRule>): List<KeywordHighlightRule> {
    require(rules.size <= MAX_KEYWORD_HIGHLIGHT_RULES) { "Too many keyword highlight rules." }
    return buildList(rules.size) {
        for (rule in rules) {
            val term = rule.phrase.trim()
            require(keywordHighlightRuleError(term, this) == null) { "Invalid or duplicate keyword highlight rule." }
            add(if (term == rule.phrase) rule else rule.copy(phrase = term))
        }
    }
}

/** Input must already have IRC formatting stripped. Match literals outside Unicode word characters. */
internal fun matchKeywordHighlight(
    visibleText: String,
    rules: List<KeywordHighlightRule>,
): KeywordMatch {
    var match = KeywordMatch.NONE
    for ((phrase, alerts) in rules) {
        if (phrase.isEmpty() || (!alerts && match == KeywordMatch.SILENT)) continue
        var start = 0
        while (start < visibleText.length) {
            val end = matchKeywordEnd(visibleText, start, phrase)
            if (end >= 0 &&
                (start == 0 || !isKeywordWordCharacter(visibleText.codePointBefore(start))) &&
                (end == visibleText.length || !isKeywordWordCharacter(visibleText.codePointAt(end)))
            ) {
                if (alerts) return KeywordMatch.ALERT
                match = KeywordMatch.SILENT
                break
            }
            start += Character.charCount(visibleText.codePointAt(start))
        }
    }
    return match
}

// Some Android versions fold String regions per UTF-16 unit. Match and deduplicate by code point instead.
private fun matchKeywordEnd(
    text: String,
    start: Int,
    term: String,
): Int {
    var textIndex = start
    var termIndex = 0
    while (termIndex < term.length) {
        if (textIndex >= text.length) return -1
        val a = text.codePointAt(textIndex)
        val b = term.codePointAt(termIndex)
        if (a != b && foldKeywordCase(a) != foldKeywordCase(b)) return -1
        textIndex += Character.charCount(a)
        termIndex += Character.charCount(b)
    }
    return textIndex
}

private fun foldKeywordCase(codePoint: Int): Int = Character.toLowerCase(Character.toUpperCase(codePoint))

private fun isKeywordWordCharacter(codePoint: Int): Boolean =
    Character.isLetterOrDigit(codePoint) ||
        when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true

            else -> false
        }
