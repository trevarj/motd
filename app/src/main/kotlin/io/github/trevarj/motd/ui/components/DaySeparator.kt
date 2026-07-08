package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Sentinel labels returned by [dayLabelKind] that map to localized "Today"/"Yesterday" strings. */
enum class DayLabelKind { TODAY, YESTERDAY, DATE }

/**
 * Localized day-separator chip built from a timestamp: resolves Today/Yesterday from string
 * resources (plans/15 #25) and otherwise a medium date. Prefer this over [DaySeparator] with a
 * pre-formatted [String] so the label follows the device locale.
 */
@Composable
fun DaySeparator(timeMs: Long, modifier: Modifier = Modifier) {
    val (kind, date) = dayLabelKind(timeMs)
    val label = when (kind) {
        DayLabelKind.TODAY -> stringResource(R.string.chat_day_today)
        DayLabelKind.YESTERDAY -> stringResource(R.string.chat_day_yesterday)
        DayLabelKind.DATE -> date
    }
    DaySeparator(label = label, modifier = modifier)
}

/** Centered date chip separating days in the message list. [label] is a pre-formatted day string. */
@Composable
fun DaySeparator(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** "— New messages —" divider rendered at the read-marker boundary. */
@Composable
fun NewMessagesDivider(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// Hoisted formatter: SimpleDateFormat is not thread-safe, so guard access. Rebuilt only when the
// default locale changes (plans/15 #28 — avoid per-row allocation on recomposition).
private var cachedLocale: Locale? = null
private var cachedDateFormat: SimpleDateFormat? = null

@Synchronized
private fun mediumDateFormat(): SimpleDateFormat {
    val locale = Locale.getDefault()
    if (cachedDateFormat == null || cachedLocale != locale) {
        cachedLocale = locale
        cachedDateFormat = SimpleDateFormat("MMMM d, yyyy", locale)
    }
    return cachedDateFormat!!
}

/**
 * Classify [timeMs] relative to [now] into Today / Yesterday / a medium date, comparing whole
 * calendar days (not a fixed 24h delta) so the boundary is correct across DST transitions
 * (plans/15 #28). The [DayLabelKind.DATE] pair carries the pre-formatted fallback string.
 */
fun dayLabelKind(timeMs: Long, now: Long = System.currentTimeMillis()): Pair<DayLabelKind, String> {
    val msg = Calendar.getInstance().apply { timeInMillis = timeMs }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = msg.get(Calendar.ERA) == today.get(Calendar.ERA) &&
        msg.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        msg.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return DayLabelKind.TODAY to ""

    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = msg.get(Calendar.ERA) == yesterday.get(Calendar.ERA) &&
        msg.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        msg.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return DayLabelKind.YESTERDAY to ""

    return DayLabelKind.DATE to synchronized(DaySeparatorLock) { mediumDateFormat().format(Date(timeMs)) }
}

/** Lock object so the shared [SimpleDateFormat] is never formatted concurrently. */
private object DaySeparatorLock

/**
 * Format an epoch-ms day label without a Compose context (non-localized Today/Yesterday). Retained
 * for callers/tests that need a plain string; UI should prefer [DaySeparator] with a timestamp so
 * Today/Yesterday localize.
 */
fun dayLabel(timeMs: Long, now: Long = System.currentTimeMillis()): String {
    val (kind, date) = dayLabelKind(timeMs, now)
    return when (kind) {
        DayLabelKind.TODAY -> "Today"
        DayLabelKind.YESTERDAY -> "Yesterday"
        DayLabelKind.DATE -> date
    }
}

/** Local-midnight epoch ms for the day containing [timeMs] (used to group by day). */
fun dayStart(timeMs: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timeMs
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@Preview
@Composable
private fun DaySeparatorPreview() {
    MotdTheme {
        DaySeparator(label = "Today")
    }
}
