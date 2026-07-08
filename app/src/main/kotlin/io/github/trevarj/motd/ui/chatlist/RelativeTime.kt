package io.github.trevarj.motd.ui.chatlist

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Telegram-style compact relative timestamp for chat-list rows.
 *
 * - < 1 min: "now"
 * - < 1 hour: "5m"
 * - same calendar day: "14:32"
 * - within the last week: weekday abbreviation ("Mon")
 * - older: "12/03" (day/month)
 */
fun relativeChatTime(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val delta = nowMs - timeMs
    if (delta < TimeUnit.MINUTES.toMillis(1)) return "now"
    if (delta < TimeUnit.HOURS.toMillis(1)) {
        return "${TimeUnit.MILLISECONDS.toMinutes(delta)}m"
    }

    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val then = Calendar.getInstance().apply { timeInMillis = timeMs }

    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) {
        return String.format(
            Locale.getDefault(),
            "%02d:%02d",
            then.get(Calendar.HOUR_OF_DAY),
            then.get(Calendar.MINUTE),
        )
    }

    if (delta < TimeUnit.DAYS.toMillis(7)) {
        return then.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())
            ?: dayMonth(then)
    }

    return dayMonth(then)
}

private fun dayMonth(cal: Calendar): String = String.format(
    Locale.getDefault(),
    "%02d/%02d",
    cal.get(Calendar.DAY_OF_MONTH),
    cal.get(Calendar.MONTH) + 1,
)
