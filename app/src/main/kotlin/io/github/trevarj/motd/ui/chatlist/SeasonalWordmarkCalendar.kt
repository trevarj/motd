package io.github.trevarj.motd.ui.chatlist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.trevarj.motd.BuildConfig
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeParseException

internal enum class SeasonalWordmarkKind { HALLOWEEN, CHRISTMAS, NEW_YEAR, ORTHODOX_EASTER }

internal data class SeasonalWordmark(
    val kind: SeasonalWordmarkKind,
    val year: Int? = null,
)

internal val LocalSeasonalWordmarkDate = staticCompositionLocalOf<LocalDate?> { null }
internal val LocalSeasonalWordmarkCountry = staticCompositionLocalOf<String?> { null }

private const val DEBUG_SEASONAL_DATE_EXTRA = "io.github.trevarj.motd.debug.SEASONAL_DATE"
private const val DEBUG_SEASONAL_COUNTRY_EXTRA = "io.github.trevarj.motd.debug.SEASONAL_COUNTRY"
private val orthodoxEasterCountries = setOf("BY", "BG", "CY", "GE", "GR", "MD", "ME", "MK", "RO", "RU", "RS", "UA")

internal fun seasonalWordmark(
    date: LocalDate,
    country: String? = null,
): SeasonalWordmark? =
    when {
        date.month == Month.OCTOBER && date.dayOfMonth in 30..31 -> SeasonalWordmark(SeasonalWordmarkKind.HALLOWEEN)
        date.month == Month.DECEMBER && date.dayOfMonth in 20..26 -> SeasonalWordmark(SeasonalWordmarkKind.CHRISTMAS)
        date.month == Month.DECEMBER && date.dayOfMonth == 31 -> SeasonalWordmark(SeasonalWordmarkKind.NEW_YEAR, date.year + 1)
        date.month == Month.JANUARY && date.dayOfMonth == 1 -> SeasonalWordmark(SeasonalWordmarkKind.NEW_YEAR, date.year)
        country in orthodoxEasterCountries && isOrthodoxEasterWeek(date) -> SeasonalWordmark(SeasonalWordmarkKind.ORTHODOX_EASTER)
        else -> null
    }

private fun isOrthodoxEasterWeek(date: LocalDate): Boolean {
    val year = date.year
    val moonOffset = (19 * (year % 19) + 15) % 30
    val sundayOffset = (2 * (year % 4) + 4 * (year % 7) - moonOffset + 34) % 7
    val gregorianOffset = year / 100 - year / 400 - 2
    val march22 = Month.MARCH.firstDayOfYear(date.isLeapYear) + 21
    val easterDay = march22 + moonOffset + sundayOffset + gregorianOffset
    return date.dayOfYear - easterDay in 0..6
}

@Composable
internal fun rememberSeasonalWordmark(): SeasonalWordmark? {
    val localCountry = LocalSeasonalWordmarkCountry.current
    LocalSeasonalWordmarkDate.current?.let { return seasonalWordmark(it, localCountry) }

    val intent = if (BuildConfig.DEBUG) LocalActivity.current?.intent else null
    val previewDate = remember(intent) { intent?.seasonalPreviewDate() }
    val country =
        localCountry ?: remember(intent, previewDate) {
            if (previewDate == null) null else intent?.getStringExtra(DEBUG_SEASONAL_COUNTRY_EXTRA)
        }

    val context = LocalContext.current

    fun currentWordmark(): SeasonalWordmark? = seasonalWordmark(previewDate ?: LocalDate.now(), country ?: LocaleManagerCompat.getSystemLocales(context)[0]?.country)

    var wordmark by remember(context, previewDate, country) { mutableStateOf(currentWordmark()) }
    LifecycleResumeEffect(context, previewDate, country) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    wordmark = currentWordmark()
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        wordmark = currentWordmark()
        onPauseOrDispose { context.unregisterReceiver(receiver) }
    }
    return wordmark
}

private fun Intent.seasonalPreviewDate(): LocalDate? =
    getStringExtra(DEBUG_SEASONAL_DATE_EXTRA)?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }
