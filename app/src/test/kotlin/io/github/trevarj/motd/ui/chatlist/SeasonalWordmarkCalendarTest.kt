package io.github.trevarj.motd.ui.chatlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SeasonalWordmarkCalendarTest {
    @Test
    fun halloweenIsOctober30Through31() {
        for (year in listOf(2026, 2027)) {
            assertNull(seasonalWordmark(LocalDate.of(year, 10, 29)))
            for (day in 30..31) {
                assertEquals(SeasonalWordmark(SeasonalWordmarkKind.HALLOWEEN), seasonalWordmark(LocalDate.of(year, 10, day)))
            }
            assertNull(seasonalWordmark(LocalDate.of(year, 11, 1)))
        }
    }

    @Test
    fun christmasIsDecember20Through26() {
        for (year in listOf(2026, 2027)) {
            assertNull(seasonalWordmark(LocalDate.of(year, 12, 19)))
            for (day in 20..26) {
                assertEquals(SeasonalWordmark(SeasonalWordmarkKind.CHRISTMAS), seasonalWordmark(LocalDate.of(year, 12, day)))
            }
            assertNull(seasonalWordmark(LocalDate.of(year, 12, 27)))
        }
    }

    @Test
    fun newYearsEveAndDayCelebrateTheSameIncomingYear() {
        for (year in listOf(2027, 2028)) {
            val celebration = SeasonalWordmark(SeasonalWordmarkKind.NEW_YEAR, year)
            assertNull(seasonalWordmark(LocalDate.of(year - 1, 12, 30)))
            assertEquals(celebration, seasonalWordmark(LocalDate.of(year - 1, 12, 31)))
            assertEquals(celebration, seasonalWordmark(LocalDate.of(year, 1, 1)))
            assertNull(seasonalWordmark(LocalDate.of(year, 1, 2)))
        }
    }

    @Test
    fun orthodoxEasterIsPaschaThroughBrightSaturdayIncludingAcrossCenturies() {
        val celebration = SeasonalWordmark(SeasonalWordmarkKind.ORTHODOX_EASTER)
        for (date in listOf("2027-05-02", "2028-04-16", "2029-04-08", "2030-04-28", "2099-04-12", "2100-05-02")) {
            val pascha = LocalDate.parse(date)
            assertNull(date, seasonalWordmark(pascha.minusDays(1), "GR"))
            for (offset in 0L..6L) {
                assertEquals(date, celebration, seasonalWordmark(pascha.plusDays(offset), "GR"))
            }
            assertNull(date, seasonalWordmark(pascha.plusDays(7), "GR"))
        }
    }

    @Test
    fun orthodoxEasterRequiresAnExplicitEligibleCountry() {
        val date = LocalDate.of(2027, 5, 2)
        val celebration = SeasonalWordmark(SeasonalWordmarkKind.ORTHODOX_EASTER)
        for (country in listOf("BY", "BG", "CY", "GE", "GR", "MD", "ME", "MK", "RO", "RU", "RS", "UA")) {
            assertEquals(country, celebration, seasonalWordmark(date, country))
        }
        for (country in listOf(null, "", "US", "JP", "ZZ", "el-GR", "el", "XK", "AM")) {
            assertNull(country, seasonalWordmark(date, country))
        }
    }

    @Test
    fun noWordmarkOnOrdinaryDates() {
        assertNull(seasonalWordmark(LocalDate.of(2026, 5, 1)))
        assertNull(seasonalWordmark(LocalDate.of(2026, 3, 31)))
    }
}
