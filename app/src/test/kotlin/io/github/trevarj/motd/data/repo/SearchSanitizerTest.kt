package io.github.trevarj.motd.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

// Plain JVM: FTS query sanitizer produces safe bare-prefix MATCH expressions.
class SearchSanitizerTest {
    @Test
    fun singleToken_getsPrefixWildcard() {
        assertEquals("foo*", SearchRepositoryImpl.sanitizeFtsQuery("foo"))
    }

    @Test
    fun multipleTokens_joinedBySpace() {
        assertEquals("foo* bar*", SearchRepositoryImpl.sanitizeFtsQuery("foo bar"))
    }

    @Test
    fun collapsesWhitespaceAndTrims() {
        assertEquals("foo* bar*", SearchRepositoryImpl.sanitizeFtsQuery("  foo   bar  "))
    }

    @Test
    fun stripsFtsOperatorChars() {
        // quotes, stars, carets, colons, parens, hyphens are removed from each token.
        assertEquals("ab*", SearchRepositoryImpl.sanitizeFtsQuery("a\"b"))
        assertEquals("foo*", SearchRepositoryImpl.sanitizeFtsQuery("*foo^"))
        assertEquals("ac*", SearchRepositoryImpl.sanitizeFtsQuery("a(c):"))
    }

    @Test
    fun blankOrOperatorOnlyInput_yieldsEmpty() {
        assertEquals("", SearchRepositoryImpl.sanitizeFtsQuery("   "))
        assertEquals("", SearchRepositoryImpl.sanitizeFtsQuery(""))
        assertEquals("", SearchRepositoryImpl.sanitizeFtsQuery("\"*^"))
    }
}
