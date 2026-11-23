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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.trevarj.motd.BuildConfig
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeParseException

internal enum class SeasonalWordmarkKind { HALLOWEEN, CHRISTMAS, NEW_YEAR }

internal data class SeasonalWordmark(
    val kind: SeasonalWordmarkKind,
    val year: Int? = null,
)

internal val LocalSeasonalWordmarkDate = staticCompositionLocalOf<LocalDate?> { null }

private const val DEBUG_SEASONAL_DATE_EXTRA = "io.github.trevarj.motd.debug.SEASONAL_DATE"

internal fun seasonalWordmark(date: LocalDate): SeasonalWordmark? =
    when {
        date.month == Month.OCTOBER && date.dayOfMonth in 30..31 -> SeasonalWordmark(SeasonalWordmarkKind.HALLOWEEN)
        date.month == Month.DECEMBER && date.dayOfMonth in 20..26 -> SeasonalWordmark(SeasonalWordmarkKind.CHRISTMAS)
        date.month == Month.DECEMBER && date.dayOfMonth == 31 -> SeasonalWordmark(SeasonalWordmarkKind.NEW_YEAR, date.year + 1)
        date.month == Month.JANUARY && date.dayOfMonth == 1 -> SeasonalWordmark(SeasonalWordmarkKind.NEW_YEAR, date.year)
        else -> null
    }

@Composable
internal fun rememberSeasonalWordmark(): SeasonalWordmark? {
    LocalSeasonalWordmarkDate.current?.let { return seasonalWordmark(it) }
    if (BuildConfig.DEBUG) {
        val intent = LocalActivity.current?.intent
        val previewDate = remember(intent) { intent?.seasonalPreviewDate() }
        if (previewDate != null) return seasonalWordmark(previewDate)
    }

    val context = LocalContext.current
    var wordmark by remember { mutableStateOf(seasonalWordmark(LocalDate.now())) }
    LifecycleResumeEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    wordmark = seasonalWordmark(LocalDate.now())
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        wordmark = seasonalWordmark(LocalDate.now())
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
