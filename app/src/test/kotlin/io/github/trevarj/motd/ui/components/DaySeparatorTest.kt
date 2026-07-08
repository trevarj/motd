package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class DaySeparatorTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    @Test fun today_is_classified_as_today() {
        val now = at(2024, Calendar.MARCH, 15, 12)
        assertEquals(DayLabelKind.TODAY, dayLabelKind(at(2024, Calendar.MARCH, 15, 8), now).first)
    }

    @Test fun previous_calendar_day_is_yesterday_regardless_of_hour() {
        val now = at(2024, Calendar.MARCH, 15, 1) // just after midnight
        // 23h earlier is still "yesterday" by calendar day even though <24h elapsed.
        assertEquals(DayLabelKind.YESTERDAY, dayLabelKind(at(2024, Calendar.MARCH, 14, 2), now).first)
    }

    @Test fun older_days_fall_back_to_a_formatted_date() {
        val now = at(2024, Calendar.MARCH, 15, 12)
        val (kind, date) = dayLabelKind(at(2024, Calendar.MARCH, 10, 12), now)
        assertEquals(DayLabelKind.DATE, kind)
        assertEquals(true, date.isNotBlank())
    }
}
