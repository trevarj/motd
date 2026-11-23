package io.github.trevarj.motd.ui.chatlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SeasonalWordmarkCalendarTest {
    @Test
    fun halloweenIsOctober30Through31() {
        assertNull(seasonalWordmark(LocalDate.of(2026, 10, 29)))
        for (day in 30..31) {
            assertEquals(SeasonalWordmark(SeasonalWordmarkKind.HALLOWEEN), seasonalWordmark(LocalDate.of(2026, 10, day)))
        }
        assertNull(seasonalWordmark(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun christmasIsDecember20Through26() {
        assertNull(seasonalWordmark(LocalDate.of(2026, 12, 19)))
        for (day in 20..26) {
            assertEquals(SeasonalWordmark(SeasonalWordmarkKind.CHRISTMAS), seasonalWordmark(LocalDate.of(2026, 12, day)))
        }
        assertNull(seasonalWordmark(LocalDate.of(2026, 12, 27)))
    }

    @Test
    fun newYearsEveAndDayCelebrateTheSameIncomingYear() {
        val celebration = SeasonalWordmark(SeasonalWordmarkKind.NEW_YEAR, 2027)
        assertNull(seasonalWordmark(LocalDate.of(2026, 12, 30)))
        assertEquals(celebration, seasonalWordmark(LocalDate.of(2026, 12, 31)))
        assertEquals(celebration, seasonalWordmark(LocalDate.of(2027, 1, 1)))
        assertNull(seasonalWordmark(LocalDate.of(2027, 1, 2)))
    }

    @Test
    fun noWordmarkOnOrdinaryDates() {
        assertNull(seasonalWordmark(LocalDate.of(2026, 5, 1)))
        assertNull(seasonalWordmark(LocalDate.of(2026, 3, 31)))
    }
}
