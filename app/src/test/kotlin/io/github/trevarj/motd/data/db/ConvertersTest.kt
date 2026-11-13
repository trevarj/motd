package io.github.trevarj.motd.data.db

import io.github.trevarj.motd.data.prefs.CompactNickBrackets
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    @Test
    fun compactNickBracketsRoundTripAndInvalidValuesInherit() {
        val converters = Converters()
        for (brackets in CompactNickBrackets.entries) {
            assertEquals(brackets.name, converters.compactNickBracketsToString(brackets))
            assertEquals(brackets, converters.stringToCompactNickBrackets(converters.compactNickBracketsToString(brackets)))
        }
        assertEquals("NONE", converters.compactNickBracketsToString(CompactNickBrackets.NONE))
        assertEquals(null, converters.compactNickBracketsToString(null))
        assertEquals(null, converters.stringToCompactNickBrackets(null))
        assertEquals(null, converters.stringToCompactNickBrackets("FUTURE_BRACKETS"))
        assertEquals(null, converters.stringToCompactNickBrackets(""))
    }

    @Test
    fun unknownLayoutDensityFallsBackToGlobalInheritance() {
        assertEquals(null, Converters().stringToLayoutDensity("FUTURE_LAYOUT"))
    }

    @Test
    fun unknownHistorySyncModeFallsBackToGlobalInheritance() {
        assertEquals(null, Converters().stringToHistorySyncMode("FUTURE_MODE"))
    }
}
