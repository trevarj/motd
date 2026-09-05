package io.github.trevarj.motd.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.HourglassFull
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.ChannelWatchDuration

/** Single source for the watch-duration wording; both watch entry points read it. */
@StringRes
fun ChannelWatchDuration.labelRes(): Int =
    when (this) {
        ChannelWatchDuration.MIN_15 -> R.string.chat_watch_15
        ChannelWatchDuration.MIN_30 -> R.string.chat_watch_30
        ChannelWatchDuration.MIN_60 -> R.string.chat_watch_60
        ChannelWatchDuration.FOREVER -> R.string.chat_watch_forever
    }

@Composable
fun ChannelWatchDuration.label(): String = stringResource(labelRes())

/** Hourglass fill tracks the duration; forever gets the infinity mark. */
val ChannelWatchDuration.icon: ImageVector
    get() =
        when (this) {
            ChannelWatchDuration.MIN_15 -> Icons.Outlined.HourglassEmpty
            ChannelWatchDuration.MIN_30 -> Icons.Outlined.HourglassBottom
            ChannelWatchDuration.MIN_60 -> Icons.Outlined.HourglassFull
            ChannelWatchDuration.FOREVER -> Icons.Outlined.AllInclusive
        }
