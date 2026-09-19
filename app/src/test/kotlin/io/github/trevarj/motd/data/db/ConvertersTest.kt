package io.github.trevarj.motd.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    @Test
    fun unknownLayoutDensityFallsBackToGlobalInheritance() {
        assertEquals(null, Converters().stringToLayoutDensity("FUTURE_LAYOUT"))
    }

    @Test
    fun unknownHistorySyncModeFallsBackToGlobalInheritance() {
        assertEquals(null, Converters().stringToHistorySyncMode("FUTURE_MODE"))
    }
}
