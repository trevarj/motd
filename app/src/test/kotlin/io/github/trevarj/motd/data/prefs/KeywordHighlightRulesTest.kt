package io.github.trevarj.motd.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KeywordHighlightRulesTest {
    @Test
    fun matchesWholeWordsAndPhrasesLiterallyIgnoringCase() {
        val rules = listOf(KeywordHighlightRule("garden party", false), KeywordHighlightRule("C++", true))
        assertEquals(KeywordMatch.SILENT, matchKeywordHighlight("(GARDEN ParTY)!", rules))
        assertEquals(KeywordMatch.ALERT, matchKeywordHighlight("garden party; learn c++.", rules))
        assertEquals(KeywordMatch.ALERT, matchKeywordHighlight("learn c++ then garden party", rules.reversed()))
        assertEquals(KeywordMatch.NONE, matchKeywordHighlight("a garden partygoer knows C+ and C++17", rules))
        assertEquals(KeywordMatch.NONE, matchKeywordHighlight("garden  party", rules))
        assertEquals(KeywordMatch.SILENT, matchKeywordHighlight("garden partygoer; garden party", rules))
        assertEquals(KeywordMatch.NONE, matchKeywordHighlight("garden party", emptyList()))
    }

    @Test
    fun wordBoundariesIncludeUnicodeLettersCombiningMarksAndUnderscores() {
        val rules = listOf(KeywordHighlightRule("cat", false))
        assertEquals(KeywordMatch.NONE, matchKeywordHighlight("écat cat_ cat9 cat\u0301 𐐀cat", rules))
        assertEquals(KeywordMatch.SILENT, matchKeywordHighlight("🐈cat🐈", rules))
    }

    @Test
    fun matchesAndDeduplicatesSupplementaryCaseVariants() {
        val rule = KeywordHighlightRule("𐐀 term", false)
        assertEquals(KeywordMatch.SILENT, matchKeywordHighlight("(𐐨 TERM)", listOf(rule)))
        assertEquals(KeywordMatch.NONE, matchKeywordHighlight("𐐨 terms", listOf(rule)))
        assertEquals(KeywordRuleError.DUPLICATE, keywordHighlightRuleError("𐐨 TERM", listOf(rule)))
    }

    @Test
    fun trimsRulesAndRejectsDuplicatesAndInvalidLimits() {
        val rule = KeywordHighlightRule("Café", false)
        assertEquals(listOf(rule), validateKeywordHighlightRules(listOf(rule.copy(phrase = "  Café  "))))
        assertEquals(KeywordRuleError.DUPLICATE, keywordHighlightRuleError("CAFÉ", listOf(rule)))
        assertThrows(IllegalArgumentException::class.java) {
            validateKeywordHighlightRules(listOf(rule, KeywordHighlightRule(" CAFÉ ", true)))
        }
        for (invalid in listOf("", "x".repeat(MAX_KEYWORD_HIGHLIGHT_LENGTH + 1), "one\ntwo", "one\u0002two")) {
            assertEquals(KeywordRuleError.INVALID_PHRASE, keywordHighlightRuleError(invalid, emptyList()))
        }
        assertNull(keywordHighlightRuleError("x".repeat(MAX_KEYWORD_HIGHLIGHT_LENGTH), emptyList()))
        val full = List(MAX_KEYWORD_HIGHLIGHT_RULES) { KeywordHighlightRule("word $it", false) }
        assertEquals(full, validateKeywordHighlightRules(full))
        assertEquals(KeywordRuleError.LIMIT, keywordHighlightRuleError("another word", full))
        assertThrows(IllegalArgumentException::class.java) { validateKeywordHighlightRules(full + rule) }
    }
}
